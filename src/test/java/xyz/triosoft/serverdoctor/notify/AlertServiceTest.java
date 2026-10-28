package xyz.triosoft.serverdoctor.notify;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AlertServiceTest {
    @Test void onlyDiscordHttpsWebhookIsAllowed() {
        assertTrue(AlertService.isValidDiscordWebhook("https://discord.com/api/webhooks/123456/longToken"));
        assertFalse(AlertService.isValidDiscordWebhook("http://discord.com/api/webhooks/123456/longToken"));
        assertFalse(AlertService.isValidDiscordWebhook("https://discord.com.evil.example/api/webhooks/123456/x"));
        assertFalse(AlertService.isValidDiscordWebhook("https://example.com/api/webhooks/123456/x"));
        assertFalse(AlertService.isValidDiscordWebhook("https://discord.com/api/webhooks/123456/longToken?x=1"));
    }
}
