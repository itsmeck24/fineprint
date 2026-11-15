package io.github.itsmeck24.fineprint.queue;

import tools.jackson.databind.JsonNode;

// every handler must be safe to run twice: a job can be retried after a crash
public interface JobHandler {

    String type();

    void run(JsonNode payload) throws Exception;

    // thrown when retrying cannot help, the job goes straight to dead
    class Permanent extends Exception {
        public Permanent(String message) {
            super(message);
        }
    }
}
