package com.jinddung2.givemeticon.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.HashMap;
import java.util.Map;

@Controller
public class ThreadPageController {

    @GetMapping("/threads")
    public String getThreadInfo(Model model) {
        Map<Thread, StackTraceElement[]> threadMap = Thread.getAllStackTraces();
        Map<String, String> threadDetails = new HashMap<>();

        threadMap.forEach((thread, stack) -> {
            if (!thread.equals(Thread.currentThread())) {
                StringBuilder stackTrace = new StringBuilder();
                for (StackTraceElement element : stack) {
                    stackTrace.append(element).append("\n");
                }
                threadDetails.put(thread.getName(), stackTrace.toString());
            }
        });

        model.addAttribute("threads", threadDetails);
        return "threads";
    }
}
