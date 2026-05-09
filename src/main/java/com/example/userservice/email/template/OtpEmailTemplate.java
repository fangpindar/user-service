package com.example.userservice.email.template;

public final class OtpEmailTemplate {

    private OtpEmailTemplate() {}

    public static final String SUBJECT = "Your login verification code";

    public static String html(String code, int ttlMinutes) {
        return """
                <!DOCTYPE html>
                <html>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                    <h2>Login verification</h2>
                    <p>Use the code below to complete your login:</p>
                    <p style="font-size: 32px; font-weight: bold; letter-spacing: 6px; margin: 24px 0; color:#2563eb;">
                        %s
                    </p>
                    <p>This code will expire in %d minutes.</p>
                    <p style="color:#888; font-size: 12px; margin-top: 32px;">
                        If you did not request this code, please ignore this email and consider changing your password.
                    </p>
                </body>
                </html>
                """.formatted(code, ttlMinutes);
    }

    public static String plainText(String code, int ttlMinutes) {
        return "Your verification code is: " + code
                + "\n\nThis code will expire in " + ttlMinutes + " minutes.";
    }
}
