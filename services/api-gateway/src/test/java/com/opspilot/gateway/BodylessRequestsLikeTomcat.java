package com.opspilot.gateway;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Makes MockMvc requests without a body look like Tomcat's: their (empty) body is already read to the end.
 *
 * <p>The gateway passes a body on only if the request's input stream isn't finished yet ({@code isFinished()}).
 * Tomcat reports a GET without a body as finished, so the real gateway sends it on without one. MockMvc's fake
 * request reports "not finished" until someone reads it, so the gateway would stream an empty body after every test
 * GET, and Java's HTTP client sometimes crashes on that (a race seen on GitHub's runners, step 3.4).
 *
 * <p>As a {@code @Component} under {@code com.opspilot.gateway} it is found by the component scan of every
 * {@code @SpringBootTest} of the gateway, and MockMvc applies every {@link MockMvcBuilderCustomizer} bean.
 */
@Component
class BodylessRequestsLikeTomcat implements MockMvcBuilderCustomizer {

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.defaultRequest(get("/").with(readEmptyBody()));   // merged into every request; its own URL wins
    }

    private static RequestPostProcessor readEmptyBody() {
        return request -> {
            byte[] content = request.getContentAsByteArray();
            if (content == null || content.length == 0) {
                try {
                    request.getInputStream().read();   // nothing there: -1, and the stream counts as finished
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return request;
        };
    }
}
