package io.github.itsmeck24.fineprint.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// when the react app is bundled into the jar, its client-side routes all load the same index.html
@Controller
@ConditionalOnWebApplication
public class AppPages {

    @GetMapping({"/login", "/watches", "/settings", "/postings/{id}"})
    public String app() {
        return "forward:/index.html";
    }
}
