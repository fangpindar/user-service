package com.example.userservice.config;

import com.example.userservice.config.properties.AppProperties;
import com.example.userservice.email.service.EmailService;
import com.example.userservice.email.service.impl.LoggingEmailService;
import com.example.userservice.email.service.impl.SendGridEmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    public EmailService emailService(AppProperties props) {
        String key = props.email().sendgridApiKey();
        boolean configured = key != null
                && !key.isBlank()
                && !key.startsWith("SG.replace")
                && !key.equals("SG.xxx");

        if (configured) {
            log.info("Using SendGridEmailService (SENDGRID_API_KEY is configured)");
            return new SendGridEmailService(props);
        }
        log.warn("SENDGRID_API_KEY not set or placeholder; falling back to LoggingEmailService " +
                "(activation links and OTPs will be printed to stdout instead of sent).");
        return new LoggingEmailService();
    }
}
