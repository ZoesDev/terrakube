package io.terrakube.api.plugin.vcs.provider.forgejo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.WebhookServiceBase;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ForgejoWebhookService extends WebhookServiceBase {

    private final ObjectMapper objectMapper;

    @Value("${io.terrakube.hostname}")
    private String hostname;

    @Value("${io.terrakube.ui.url}")
    private String uiUrl;

    public ForgejoWebhookService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WebhookResult processWebhook(String jsonPayload, Map<String, String> headers, String token, Vcs vcs) {
        return handleWebhook(jsonPayload, headers, token, "x-forgejo-signature", JobVia.Github.name(),
                (payload, result, headerMap) -> handleEvent(payload, result, headerMap, vcs));
    }

    private WebhookResult handleEvent(String jsonPayload, WebhookResult result, Map<String, String> headers, Vcs vcs) {
        // Forgejo sends X-Forgejo-Event; fall back to X-Gitea-Event for Gitea compatibility
        String event = headers.get("x-forgejo-event");
        if (event == null) {
            event = headers.get("x-gitea-event");
        }
        if (event == null) {
            log.error("No Forgejo/Gitea event header found");
            result.setValid(false);
            return result;
        }

        result.setEvent(event);

        if ("ping".equals(event)) {
            return result;
        }

        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(jsonPayload);
        } catch (Exception e) {
            log.error("Error parsing Forgejo webhook JSON payload", e);
            result.setValid(false);
            return result;
        }

        switch (event) {
            case "push":
                return handlePushEvent(jsonPayload, result, rootNode);
            case "pull_request":
                return handlePullRequestEvent(result, rootNode, vcs);
            case "issue_comment":
                return handleIssueCommentEvent(result, rootNode, vcs);
            case "release":
                return handleReleaseEvent(result, rootNode);
            default:
                log.warn("Unsupported Forgejo event type: {}", event);
                result.setValid(false);
                return result;
        }
    }

    private WebhookResult handlePushEvent(String jsonPayload, WebhookResult result, JsonNode rootNode) {
        String[] ref = rootNode.path("ref").asText().split("/");
        String[] extractedBranch = Arrays.copyOfRange(ref, 2, ref.length);
        result.setBranch(String.join("/", extractedBranch));
        result.setCreatedBy(rootNode.path("pusher").path("email").asText());

        List<String> fileChanges = new ArrayList<>();
        try {
            // Parse head commit SHA
            JsonNode headCommit = rootNode.path("head_commit");
            if (!headCommit.isMissingNode()) {
                result.setCommit(headCommit.path("id").asText());
            }
            // Collect changed files from all commits
            JsonNode commits = rootNode.path("commits");
            if (commits.isArray()) {
                for (JsonNode commit : commits) {
                    addFilesFromNode(fileChanges, commit.path("added"));
                    addFilesFromNode(fileChanges, commit.path("removed"));
                    addFilesFromNode(fileChanges, commit.path("modified"));
                }
            }
        } catch (Exception e) {
            log.error("Error parsing Forgejo push payload", e);
        }
        result.setFileChanges(fileChanges);
        return result;
    }

    private void addFilesFromNode(List<String> files, JsonNode arrayNode) {
        if (arrayNode.isArray()) {
            for (JsonNode file : arrayNode) {
                files.add(file.asText());
            }
        }
    }

    private WebhookResult handlePullRequestEvent(WebhookResult result, JsonNode rootNode, Vcs vcs) {
        String action = rootNode.path("action").asText();
        if ("opened".equals(action) || "synchronize".equals(action) || "reopened".equals(action)) {
            result.setPrNumber(rootNode.path("number").asInt());
            result.setCommit(rootNode.path("pull_request").path("head").path("sha").asText());
            result.setBranch(rootNode.path("pull_request").path("head").path("ref").asText());
            result.setCreatedBy(rootNode.path("pull_request").path("user").path("login").asText());

            String repoOwner = rootNode.path("repository").path("owner").path("login").asText();
            String repoName = rootNode.path("repository").path("name").asText();
            int prNumber = rootNode.path("number").asInt();
            result.setFileChanges(getPrFileChanges(vcs, repoOwner, repoName, prNumber));
        } else {
            log.info("Ignoring pull_request action '{}' for Forgejo", action);
            result.setValid(false);
        }
        return result;
    }

    private WebhookResult handleIssueCommentEvent(WebhookResult result, JsonNode rootNode, Vcs vcs) {
        String action = rootNode.path("action").asText();
        if (!"created".equals(action)) {
            result.setValid(false);
            return result;
        }

        JsonNode issueNode = rootNode.path("issue");
        // Only handle comments on pull requests (issue with pull_request field)
        if (!issueNode.has("pull_request")) {
            result.setValid(false);
            return result;
        }

        String commentBody = rootNode.path("comment").path("body").asText().trim();
        String command = parseTerrakubeCommand(commentBody);
        if (command == null) {
            result.setValid(false);
            return result;
        }

        result.setPrComment(true);
        result.setCommentBody(commentBody);
        result.setCommentCommand(command);
        result.setPrNumber(issueNode.path("number").asInt());
        result.setCreatedBy(rootNode.path("comment").path("user").path("login").asText());

        // Fetch PR details to get branch and commit SHA
        String repoOwner = rootNode.path("repository").path("owner").path("login").asText();
        String repoName = rootNode.path("repository").path("name").asText();
        int prNumber = issueNode.path("number").asInt();

        String prApiUrl = vcs.getApiUrl() + "/repos/" + repoOwner + "/" + repoName + "/pulls/" + prNumber;
        ResponseEntity<String> prResponse = callForgejoApi(vcs.getAccessToken(), null, prApiUrl, HttpMethod.GET);
        if (prResponse != null && prResponse.getStatusCode().is2xxSuccessful()) {
            try {
                JsonNode prNode = objectMapper.readTree(prResponse.getBody());
                result.setCommit(prNode.path("head").path("sha").asText());
                result.setBranch(prNode.path("head").path("ref").asText());
                result.setFileChanges(getPrFileChanges(vcs, repoOwner, repoName, prNumber));
            } catch (Exception e) {
                log.error("Error fetching PR details for issue_comment on Forgejo", e);
                result.setValid(false);
            }
        } else {
            log.error("Failed to fetch PR details for Forgejo issue_comment");
            result.setValid(false);
        }
        return result;
    }

    private WebhookResult handleReleaseEvent(WebhookResult result, JsonNode rootNode) {
        String action = rootNode.path("action").asText();
        if ("published".equals(action)) {
            result.setValid(true);
            result.setRelease(true);
            result.setBranch(rootNode.path("release").path("tag_name").asText());
            result.setCreatedBy("system");
            log.info("Forgejo release published: {}", result.getBranch());
        } else {
            log.info("Ignoring release action '{}' for Forgejo", action);
            result.setValid(false);
        }
        return result;
    }

    public String createOrUpdateWebhook(Workspace workspace, Webhook webhook) {
        String id = webhook.getRemoteHookId();
        String secret = Base64.getEncoder()
                .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));
        String webhookUrl = String.format("https://%s/webhook/v1/%s", hostname, webhook.getId());
        String[] ownerAndRepo = extractOwnerAndRepo(workspace.getSource());
        String baseUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepo) + "/hooks";

        boolean hasPrWorkflow = webhook.getEvents() != null && webhook.getEvents().stream()
                .anyMatch(WebhookEvent::isPrWorkflowEnabled);

        String body;
        String apiUrl;
        HttpMethod method;

        if (id != null) {
            List<String> events = buildEventsList(webhook, hasPrWorkflow);
            body = "{\"active\":true,\"events\":" + toJsonArray(events) + "}";
            apiUrl = baseUrl + "/" + id;
            method = HttpMethod.PATCH;
        } else {
            List<String> events = buildEventsList(webhook, hasPrWorkflow);
            body = "{\"type\":\"forgejo\",\"active\":true,\"events\":" + toJsonArray(events)
                    + ",\"config\":{\"content_type\":\"json\",\"url\":\"" + webhookUrl
                    + "\",\"secret\":\"" + secret + "\"}}";
            apiUrl = baseUrl;
            method = HttpMethod.POST;
        }

        ResponseEntity<String> response = callForgejoApi(workspace.getVcs().getAccessToken(), body, apiUrl, method);
        if (response != null && (response.getStatusCode().value() == 201 || response.getStatusCode().value() == 200)) {
            if (id == null) {
                try {
                    JsonNode node = objectMapper.readTree(response.getBody());
                    id = node.path("id").asText();
                } catch (Exception e) {
                    log.error("Error parsing Forgejo webhook creation response", e);
                }
            }
            log.info("Forgejo webhook created/updated for workspace {}/{} with id {}",
                    workspace.getOrganization().getName(), workspace.getName(), id);
        } else {
            log.error("Failed to create/update Forgejo webhook: {}",
                    response != null ? response.getBody() : "no response");
        }
        return id;
    }

    public void deleteWebhook(Workspace workspace, String webhookRemoteId) {
        String[] ownerAndRepo = extractOwnerAndRepo(workspace.getSource());
        String apiUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepo)
                + "/hooks/" + webhookRemoteId;

        ResponseEntity<String> response = callForgejoApi(workspace.getVcs().getAccessToken(), "", apiUrl,
                HttpMethod.DELETE);
        if (response != null && response.getStatusCode().value() == 204) {
            log.info("Forgejo webhook {} deleted for {}", webhookRemoteId, workspace.getSource());
        } else {
            log.warn("Failed to delete Forgejo webhook {} for {}: {}", webhookRemoteId, workspace.getSource(),
                    response != null ? response.getBody() : "no response");
        }
    }

    public void sendCommitStatus(Job job, JobStatus jobStatus) {
        Workspace workspace = job.getWorkspace();
        String[] ownerAndRepo = extractOwnerAndRepo(workspace.getSource());
        String jobUrl = String.format("%s/organizations/%s/workspaces/%s/runs/%s", uiUrl,
                workspace.getOrganization().getId(), workspace.getId(), job.getId());

        String state;
        String description;
        switch (jobStatus) {
            case completed:
                state = "success";
                description = "Your task has been completed successfully.";
                break;
            case failed:
            case rejected:
            case cancelled:
                state = "failure";
                description = "Your task has failed.";
                break;
            case unknown:
                state = "error";
                description = "Your task ran into errors.";
                break;
            default:
                state = "pending";
                description = "Your task is in Terrakube queue.";
                break;
        }

        String context = "Terrakube - " + workspace.getOrganization().getName() + " - " + workspace.getName();
        String body = "{\"state\":\"" + state + "\",\"description\":\"" + description
                + "\",\"target_url\":\"" + jobUrl + "\",\"context\":\"" + context + "\"}";

        String apiUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepo)
                + "/statuses/" + job.getCommitId();

        log.info("Sending commit status '{}' to Forgejo for commit {}", state, job.getCommitId());
        ResponseEntity<String> response = callForgejoApi(workspace.getVcs().getAccessToken(), body, apiUrl,
                HttpMethod.POST);
        if (response != null && response.getStatusCode().value() == 201) {
            log.info("Commit status sent successfully to Forgejo");
        } else {
            log.error("Failed to send commit status to Forgejo: {}",
                    response != null ? response.getBody() : "no response");
        }
    }

    public String postPrComment(Job job, String markdownBody) {
        Workspace workspace = job.getWorkspace();
        String[] ownerAndRepo = extractOwnerAndRepo(workspace.getSource());
        String apiUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepo)
                + "/issues/" + job.getPrNumber() + "/comments";

        String body = "{\"body\":\"" + escapeJsonString(markdownBody) + "\"}";
        ResponseEntity<String> response = callForgejoApi(workspace.getVcs().getAccessToken(), body, apiUrl,
                HttpMethod.POST);
        if (response != null && response.getStatusCode().is2xxSuccessful()) {
            try {
                JsonNode node = objectMapper.readTree(response.getBody());
                String commentId = node.path("id").asText();
                log.info("PR comment posted on Forgejo PR #{} in workspace {}", job.getPrNumber(),
                        workspace.getName());
                return commentId;
            } catch (JsonProcessingException e) {
                log.error("Error parsing Forgejo PR comment response", e);
            }
        } else {
            log.error("Failed to post PR comment on Forgejo PR #{}", job.getPrNumber());
        }
        return null;
    }

    private List<String> getPrFileChanges(Vcs vcs, String repoOwner, String repoName, int prNumber) {
        List<String> changedFiles = new ArrayList<>();
        String apiUrl = vcs.getApiUrl() + "/repos/" + repoOwner + "/" + repoName + "/pulls/" + prNumber + "/files";
        ResponseEntity<String> response = callForgejoApi(vcs.getAccessToken(), null, apiUrl, HttpMethod.GET);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.warn("Failed to fetch PR file changes from Forgejo");
            return changedFiles;
        }
        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            for (JsonNode file : node) {
                changedFiles.add(file.path("filename").asText());
            }
        } catch (Exception e) {
            log.error("Error parsing Forgejo PR file changes", e);
        }
        return changedFiles;
    }

    private List<String> buildEventsList(Webhook webhook, boolean hasPrWorkflow) {
        List<String> events = webhook.getEvents().stream()
                .map(e -> String.valueOf(e.getEvent()).toLowerCase())
                .distinct()
                .collect(Collectors.toList());
        if (hasPrWorkflow && !events.contains("issue_comment")) {
            events.add("issue_comment");
        }
        // Forgejo uses "pull_request" not "pull_request" (same) — ensure push is included
        return events;
    }

    private String toJsonArray(List<String> items) {
        return "[" + items.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]";
    }

    private ResponseEntity<String> callForgejoApi(String accessToken, String body, String apiUrl, HttpMethod method) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "token " + accessToken);
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");
        return makeApiRequest(headers, body != null ? body : "", apiUrl, method);
    }
}
