package sg.schoolmatch.config;

import java.net.http.HttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The {@link RestClient.Builder} for outside HTTP calls (Google, OneMap, data.gov.sg; NFR-MAIN-02).
 * Only {@code boundary.external} classes inject it. Timeouts come from {@code app.http.*}
 * (connect 5 s, read 15 s); JSON uses the application's Jackson settings.
 * <p>
 * Prototype scope: every injection gets a fresh builder, so one client's {@code baseUrl(...)} or
 * {@code defaultHeader(...)} never leaks into another. Tests can bind a {@code MockRestServiceServer} to it:
 * <pre>
 * RestClient.Builder builder = RestClient.builder();
 * MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
 * </pre>
 */
@Configuration
public class RestClientConfig {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder(AppProperties props,
                                                ObjectProvider<ClientHttpMessageConvertersCustomizer> converters) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.http().connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)   // data.gov.sg download links redirect
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.http().readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(builder -> {
                    builder.registerDefaults();   // Spring's defaults, then Boot's (its Jackson JsonMapper)
                    converters.orderedStream().forEach(c -> c.customize(builder));
                });
    }
}
