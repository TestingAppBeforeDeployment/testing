package com.reddit.clone.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.reddit.clone.service.RedditService;

@RestController
@RequestMapping("/reddit")
@CrossOrigin(origins = "*")
public class RedditController {

    @Autowired
    private RedditService service;

    @GetMapping("/{query}")
    public String getPostFromQuery(@PathVariable String query) {
        return service.getPost(query);
    }
    
    @GetMapping("/user/{username}")
    public String getUserData(@PathVariable String username) {
        return service.getUser(username);
    }
    
    @GetMapping("/user/{username}/post")
    public String getUserPost(
            @PathVariable String username,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.getUserPosts(username, page, size);
    }
}