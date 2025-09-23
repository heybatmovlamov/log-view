package stream.api.adapter.log;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.net.URI;

@Component
public class OpenBrowserOnStartup implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(new URI("http://localhost:8090/log-view.html"));
        }
    }
}
