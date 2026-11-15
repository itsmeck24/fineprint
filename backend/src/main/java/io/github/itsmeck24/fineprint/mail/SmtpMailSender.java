package io.github.itsmeck24.fineprint.mail;

import java.util.Properties;
import java.util.UUID;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

// plain smtp, used with mailpit on a laptop. smtp itself has no idempotency, so a crash between sending
// and recording can still give a duplicate here. the header is the one resend reads over smtp.
public class SmtpMailSender implements MailSender {

    private final Session session;
    private final String from;

    public SmtpMailSender(String host, int port, String from) {
        Properties p = new Properties();
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", Integer.toString(port));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "20000");
        this.session = Session.getInstance(p);
        this.from = from;
    }

    @Override
    public String send(Message m) throws Exception {
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(RecipientType.TO, new InternetAddress(m.to()));
        msg.setSubject(m.subject(), "UTF-8");
        msg.setText(m.body(), "UTF-8");
        String id = "<" + UUID.randomUUID() + "@fineprint.localhost>";
        msg.setHeader("Resend-Idempotency-Key", m.idempotencyKey());
        msg.setHeader("X-FinePrint-Key", m.idempotencyKey());
        msg.saveChanges();
        msg.setHeader("Message-ID", id);
        Transport.send(msg);
        return id;
    }
}
