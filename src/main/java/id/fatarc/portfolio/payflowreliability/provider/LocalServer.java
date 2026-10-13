package id.fatarc.portfolio.payflowreliability.provider;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** The port this application actually listens on, for talking to the in-process provider simulator. */
@Component
public class LocalServer {
    private volatile int port = -1;

    @EventListener
    public void onStarted(WebServerInitializedEvent event) {
        port = event.getWebServer().getPort();
    }

    public String baseUrl() {
        if (port <= 0) {
            throw new IllegalStateException("The web server has not started yet");
        }
        return "http://localhost:" + port;
    }
}
