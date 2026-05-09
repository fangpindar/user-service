package com.example.userservice.email.template;

public final class ActivationEmailTemplate {

    private ActivationEmailTemplate() {}

    public static final String SUBJECT = "Please activate your account";

    public static String html(String activationLink) {
        return """
                <!DOCTYPE html>
                <html>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                    <h2>Welcome!</h2>
                    <p>Thanks for signing up. Please confirm your email by clicking the button below:</p>
                    <p style="margin: 24px 0;">
                        <a href="%s"
                           style="background:#2563eb;color:#fff;padding:12px 24px;text-decoration:none;border-radius:4px;">
                            Activate my account
                        </a>
                    </p>
                    <p>Or copy and paste this link into your browser:</p>
                    <p style="color:#555; word-break: break-all;">%s</p>
                    <p style="color:#888; font-size: 12px; margin-top: 32px;">
                        This link will expire in 24 hours. If you did not sign up, you can safely ignore this email.
                    </p>
                </body>
                </html>
                """.formatted(activationLink, activationLink);
    }

    public static String plainText(String activationLink) {
        return "Please activate your account by visiting this link:\n\n"
                + activationLink
                + "\n\nThis link will expire in 24 hours.";
    }
}
