package com.example.userservice.email.service.impl;

import com.example.userservice.config.properties.AppProperties;
import com.example.userservice.email.service.EmailService;
import com.example.userservice.email.template.ActivationEmailTemplate;
import com.example.userservice.email.template.OtpEmailTemplate;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class SendGridEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailService.class);

    private final SendGrid sendGrid;
    private final String fromAddress;

    public SendGridEmailService(AppProperties props) {
        this.sendGrid = new SendGrid(props.email().sendgridApiKey());
        this.fromAddress = props.email().from();
    }

    @Override
    public void sendActivationEmail(String toEmail, String activationLink) {
        sendEmail(
                toEmail,
                ActivationEmailTemplate.SUBJECT,
                ActivationEmailTemplate.plainText(activationLink),
                ActivationEmailTemplate.html(activationLink)
        );
    }

    @Override
    public void sendOtpEmail(String toEmail, String code, int ttlMinutes) {
        sendEmail(
                toEmail,
                OtpEmailTemplate.SUBJECT,
                OtpEmailTemplate.plainText(code, ttlMinutes),
                OtpEmailTemplate.html(code, ttlMinutes)
        );
    }

    private void sendEmail(String to, String subject, String plainText, String html) {
        Email from = new Email(fromAddress);
        Email recipient = new Email(to);
        Mail mail = new Mail(from, subject, recipient, new Content("text/plain", plainText));
        mail.addContent(new Content("text/html", html));

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            if (response.getStatusCode() >= 400) {
                log.error("SendGrid send failed: status={}, body={}", response.getStatusCode(), response.getBody());
                throw new EmailSendException("Failed to send email: status " + response.getStatusCode());
            }
            log.info("Email sent to {} (status={})", to, response.getStatusCode());
        } catch (IOException ex) {
            log.error("SendGrid IO error sending to {}", to, ex);
            throw new EmailSendException("Failed to send email", ex);
        }
    }

    public static class EmailSendException extends RuntimeException {
        public EmailSendException(String message) { super(message); }
        public EmailSendException(String message, Throwable cause) { super(message, cause); }
    }
}
