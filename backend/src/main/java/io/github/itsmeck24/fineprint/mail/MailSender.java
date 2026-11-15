package io.github.itsmeck24.fineprint.mail;

// idempotencyKey is the same on every retry of one notification, so a provider that supports keys
// can drop a repeat that follows a crash between sending and recording it.
public interface MailSender {

    record Message(String to, String subject, String body, String idempotencyKey) {
    }

    String send(Message m) throws Exception;

    // 4xx from the provider: retrying the same message will not help
    class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }
}
