package com.example.userservice.auth.controller;

import com.example.userservice.auth.service.ActivationService;
import com.example.userservice.common.exception.domain.ActivationTokenInvalidException;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "1. Authentication")
@RestController
@RequestMapping("/api/v1/auth")
public class ActivationController {

    private final ActivationService activationService;

    public ActivationController(ActivationService activationService) {
        this.activationService = activationService;
    }

    @Operation(
            summary = "Activation confirmation page (Step 1: GET)",
            description = "Returns an HTML confirmation page. **Does NOT activate the account.** " +
                    "This GET endpoint exists to defeat email link scanners (e.g. corporate antivirus, " +
                    "Outlook Safe Links) that pre-fetch URLs. The actual activation happens when the user " +
                    "clicks the button on this page, which submits a POST to /api/v1/auth/activate.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Confirmation page rendered",
                            content = @Content(mediaType = MediaType.TEXT_HTML_VALUE,
                                    schema = @Schema(implementation = String.class))),
                    @ApiResponse(responseCode = "400", description = "Token invalid/expired/used",
                            content = @Content(mediaType = MediaType.TEXT_HTML_VALUE))
            })
    @GetMapping(value = "/activate", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> showConfirmationPage(
            @Parameter(description = "Activation token from email", required = true)
            @RequestParam("token") @NotBlank String token) {
        try {
            activationService.validateTokenReadOnly(token);
        } catch (ActivationTokenInvalidException ex) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorPage(ex.getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(confirmationPage(token));
    }

    @Operation(
            summary = "Activate account (Step 2: POST)",
            description = "Performs the actual activation. This is what the confirmation page submits. " +
                    "Marks the user as ACTIVE and the activation token as used. " +
                    "Idempotent for already-active users.")
    @PostMapping(value = "/activate")
    public ResponseEntity<ActivationResponse> activate(@RequestBody ActivateRequest request) {
        activationService.activate(request.token());
        return ResponseEntity.ok(new ActivationResponse(
                "Account activated. You can now log in.", true));
    }

    @Schema(description = "Activation request body")
    public record ActivateRequest(
            @Schema(description = "Token from email link") @NotBlank
            @JsonProperty("token") String token) {}

    @Schema(description = "Activation result")
    public record ActivationResponse(String message, boolean activated) {}

    private String confirmationPage(String token) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>Activate your account</title>
                  <style>
                    body { font-family: Arial, sans-serif; background:#f5f7fb; margin:0; padding:40px 16px; color:#222; }
                    .card { max-width: 480px; margin: 0 auto; background:#fff; padding:32px; border-radius:8px; box-shadow:0 4px 12px rgba(0,0,0,0.06); }
                    h1 { margin: 0 0 16px; color:#111; }
                    p { line-height: 1.6; color:#444; }
                    button { background:#2563eb; color:#fff; border:0; padding:14px 28px; font-size:16px; border-radius:6px; cursor:pointer; }
                    button:hover { background:#1d4ed8; }
                    .ok { color:#16a34a; display:none; margin-top: 16px; font-weight: 600; }
                    .err { color:#dc2626; display:none; margin-top: 16px; font-weight: 600; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Confirm account activation</h1>
                    <p>Click the button below to activate your account. This extra step protects against
                       email link scanners that may have pre-fetched this page.</p>
                    <button id="confirm">Activate my account</button>
                    <div class="ok" id="ok">✓ Account activated. You can now log in.</div>
                    <div class="err" id="err"></div>
                  </div>
                  <script>
                    const token = %s;
                    const btn = document.getElementById('confirm');
                    const ok  = document.getElementById('ok');
                    const err = document.getElementById('err');
                    btn.addEventListener('click', async () => {
                      btn.disabled = true;
                      err.style.display = 'none';
                      try {
                        const res = await fetch('/api/v1/auth/activate', {
                          method: 'POST',
                          headers: { 'Content-Type': 'application/json' },
                          body: JSON.stringify({ token })
                        });
                        if (res.ok) {
                          ok.style.display = 'block';
                          btn.style.display = 'none';
                        } else {
                          const data = await res.json().catch(() => ({}));
                          err.textContent = data.message || 'Activation failed.';
                          err.style.display = 'block';
                          btn.disabled = false;
                        }
                      } catch (e) {
                        err.textContent = 'Network error: ' + e.message;
                        err.style.display = 'block';
                        btn.disabled = false;
                      }
                    });
                  </script>
                </body>
                </html>
                """.formatted(jsonString(token));
    }

    private String errorPage(String message) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>Activation error</title>
                  <style>
                    body { font-family: Arial, sans-serif; background:#f5f7fb; margin:0; padding:40px 16px; }
                    .card { max-width: 480px; margin: 0 auto; background:#fff; padding:32px; border-radius:8px; }
                    h1 { color:#dc2626; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Activation problem</h1>
                    <p>%s</p>
                    <p>If your link expired, please request a new activation email.</p>
                  </div>
                </body>
                </html>
                """.formatted(escapeHtml(message));
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
    }
}
