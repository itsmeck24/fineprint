package io.github.itsmeck24.fineprint.api;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import io.github.itsmeck24.fineprint.store.NotificationStore;
import io.github.itsmeck24.fineprint.store.UserStore;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class MeController {

    public record Profile(String gradMonth, String degreeLevel) {
    }

    private final UserStore users;
    private final NotificationStore notifications;
    private final boolean emailAlerts;

    public MeController(UserStore users, NotificationStore notifications, FinePrintProperties props) {
        this.users = users;
        this.notifications = notifications;
        // with the log provider alerts are only listed in the app, so the app should not promise email
        this.emailAlerts = !"log".equals(props.mail().provider());
    }

    @GetMapping("/notifications")
    public List<NotificationStore.Row> notifications(@AuthenticationPrincipal Jwt jwt) {
        return notifications.forUser(user(jwt).id(), 50);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        UserStore.User u = user(jwt);
        return Map.of("email", u.email(), "gradMonth", u.gradMonth().toString(), "degreeLevel", u.degreeLevel(),
                "emailAlerts", emailAlerts);
    }

    @PutMapping("/me")
    public Map<String, Object> update(@AuthenticationPrincipal Jwt jwt, @RequestBody Profile p) {
        UserStore.User u = user(jwt);
        YearMonth grad = AuthController.parseMonth(p.gradMonth());
        users.updateProfile(u.id(), grad, AuthController.parseDegree(p.degreeLevel()));
        return me(jwt);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        users.delete(user(jwt).id());
        return ResponseEntity.noContent().build();
    }

    private UserStore.User user(Jwt jwt) {
        return users.byId(Long.parseLong(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
