package io.github.itsmeck24.fineprint.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailSender mailSender(FinePrintProperties props, JsonMapper json) {
        FinePrintProperties.Mail m = props.mail();
        return switch (m.provider()) {
            case "smtp" -> new SmtpMailSender(m.smtpHost(), m.smtpPort(), m.from());
            case "resend" -> new ResendMailSender(m.resendBase(), m.resendApiKey(), m.from(), json);
            default -> msg -> {
                log.info("mail (log only) to {}: {}", msg.to(), msg.subject());
                return "log:" + msg.idempotencyKey();
            };
        };
    }
}
