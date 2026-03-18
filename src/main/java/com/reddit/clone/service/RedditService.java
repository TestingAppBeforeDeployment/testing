package com.reddit.clone.service;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class RedditService {

    private static final String REDDIT_BASE_URL = "https://www.reddit.com";
    private static final int REDDIT_LISTING_LIMIT = 100;
    private static final int MAX_FETCHED_POSTS = 2000;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpEntity<String> buildEntity() {
        HttpHeaders headers = new HttpHeaders();
        // Reddit is stricter on cloud traffic; provide an explicit app-style user agent.
        String configuredUserAgent = System.getenv("REDDIT_USER_AGENT");
        String userAgent = (configuredUserAgent == null || configuredUserAgent.isBlank())
            ? "web:reddit-clone:v1.0 (by /u/reddit_clone_user)"
            : configuredUserAgent;
        headers.set("User-Agent", userAgent);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private String fetchUrl(String url) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    buildEntity(),
                    String.class
            );
            return response.getBody() == null ? "{}" : response.getBody();
        } catch (RestClientResponseException ex) {
            String status = String.valueOf(ex.getStatusCode().value());
            String body = ex.getResponseBodyAsString();
            if (body != null && body.length() > 300) {
                body = body.substring(0, 300);
            }
            throw new IllegalStateException("Reddit upstream error. status=" + status + ", body=" + body, ex);
        }
    }

    public String getPost(String query) {
        String url = UriComponentsBuilder
            .fromUriString(REDDIT_BASE_URL + "/r/{query}.json")
                .queryParam("raw_json", 1)
                .buildAndExpand(query)
                .toUriString();
        return fetchUrl(url);
    }
    
    public String getUser(String username) {
        String url = UriComponentsBuilder
            .fromUriString(REDDIT_BASE_URL + "/user/{username}/about.json")
                .queryParam("raw_json", 1)
                .buildAndExpand(username)
                .toUriString();
        return fetchUrl(url);
    }
    
    public String getUserPosts(String username, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);

        ArrayNode collectedPosts = objectMapper.createArrayNode();
        String after = null;

        try {
            while (collectedPosts.size() < MAX_FETCHED_POSTS) {
                UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString(REDDIT_BASE_URL + "/user/{username}/submitted.json")
                        .queryParam("limit", REDDIT_LISTING_LIMIT)
                        .queryParam("sort", "new")
                        .queryParam("raw_json", 1);

                if (after != null && !after.isBlank()) {
                    builder.queryParam("after", after);
                }

                String url = builder.buildAndExpand(username).toUriString();
                JsonNode root = objectMapper.readTree(fetchUrl(url));
                JsonNode data = root.path("data");
                JsonNode children = data.path("children");

                if (!children.isArray() || children.isEmpty()) {
                    break;
                }

                for (JsonNode child : children) {
                    JsonNode postData = child.path("data");
                    if (!postData.isMissingNode() && !postData.isNull()) {
                        collectedPosts.add(postData.deepCopy());
                        if (collectedPosts.size() >= MAX_FETCHED_POSTS) {
                            break;
                        }
                    }
                }

                JsonNode afterNode = data.path("after");
                after = (afterNode.isMissingNode() || afterNode.isNull()) ? null : afterNode.asText(null);
                if (after == null || after.isBlank()) {
                    break;
                }
            }

            int totalPosts = collectedPosts.size();
            int totalPages = totalPosts == 0 ? 1 : (int) Math.ceil((double) totalPosts / safeSize);
            int effectivePage = Math.min(safePage, totalPages);
            int startIndex = Math.min((effectivePage - 1) * safeSize, totalPosts);
            int endIndex = Math.min(startIndex + safeSize, totalPosts);

            ArrayNode items = objectMapper.createArrayNode();
            for (int i = startIndex; i < endIndex; i++) {
                items.add(collectedPosts.get(i));
            }

            ObjectNode response = objectMapper.createObjectNode();
            response.put("username", username);
            response.put("page", effectivePage);
            response.put("pageSize", safeSize);
            response.put("totalPosts", totalPosts);
            response.put("totalPages", totalPages);
            response.put("hasPreviousPage", effectivePage > 1);
            response.put("hasNextPage", effectivePage < totalPages);
            response.set("items", items);

            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to process Reddit response", ex);
        }
    }
}