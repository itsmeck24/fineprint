package io.github.itsmeck24.fineprint.api;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import io.github.itsmeck24.fineprint.store.UserStore;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/auth")
public class AuthController {

    public record Register(String email, String password, String gradMonth, String degreeLevel) {
    }

    public record Login(String email, String password) {
    }

    static final Set<String> DEGREES = Set.of("bachelor", "master", "phd");

    private final UserStore users;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwt;
    private final FinePrintProperties props;
    // compared against when the email is unknown, so both cases take about as long
    private final String dummyHash;

    public AuthController(UserStore users, PasswordEncoder passwords, JwtEncoder jwt, FinePrintProperties props) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.props = props;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@RequestBody Register r) {
        String email = normalizeEmail(r.email());
        if (r.password() == null || r.password().length() < 8 || r.password().length() > 200) {
            throw bad("password must be 8 to 200 characters");
        }
        YearMonth grad = parseMonth(r.gradMonth());
        String degree = parseDegree(r.degreeLevel());
        long id = users.create(email, passwords.encode(r.password()), grad, degree)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "email already registered"));
        return Map.of("token", token(id));
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody Login l) {
        Optional<UserStore.User> u = users.byEmail(normalizeEmail(l.email()));
        String password = l.password() == null ? "" : l.password();
        boolean ok = passwords.matches(password, u.map(UserStore.User::passwordHash).orElse(dummyHash));
        if (u.isEmpty() || !ok) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wrong email or password");
        }
        return Map.of("token", token(u.get().id()));
    }

    String token(long userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(now.plus(props.jwt().ttl()))
                .build();
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    static String normalizeEmail(String email) {
        if (email == null || email.length() > 254 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw bad("a valid email is required");
        }
        return email.strip().toLowerCase();
    }

    static YearMonth parseMonth(String s) {
        try {
            YearMonth m = YearMonth.parse(s == null ? "" : s);
            if (m.getYear() < 2000 || m.getYear() > 2100) {
                throw bad("gradMonth looks wrong");
            }
            return m;
        } catch (DateTimeParseException e) {
            throw bad("gradMonth must look like 2027-05");
        }
    }

    static String parseDegree(String s) {
        if (s == null || !DEGREES.contains(s)) {
            throw bad("degreeLevel must be bachelor, master or phd");
        }
        return s;
    }

    static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
