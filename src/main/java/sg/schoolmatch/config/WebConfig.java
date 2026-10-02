package sg.schoolmatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.OriginCheckInterceptor;

/**
 * Registers the two interceptors. {@link OriginCheckInterceptor} runs first, on every path: a form post from
 * another website gets 403 (DC-72). {@link AuthInterceptor} runs on the pages that need login
 * (FR-SHORTLIST-03, NFR-SEC-05, DC-07). {@code /schools/*}{@code /shortlist} is only protected for POST; the
 * interceptor checks the method itself.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Paths that require an authenticated session. */
    public static final String[] LOGIN_REQUIRED_PATHS = {
            "/profile/**", "/shortlist/**", "/compare/**", "/plan/**", "/recommendations/**",
            "/logout", "/schools/*/shortlist"
    };

    private final OriginCheckInterceptor originCheckInterceptor;
    private final AuthInterceptor authInterceptor;

    public WebConfig(OriginCheckInterceptor originCheckInterceptor, AuthInterceptor authInterceptor) {
        this.originCheckInterceptor = originCheckInterceptor;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(originCheckInterceptor).addPathPatterns("/**");
        registry.addInterceptor(authInterceptor).addPathPatterns(LOGIN_REQUIRED_PATHS);
    }
}
