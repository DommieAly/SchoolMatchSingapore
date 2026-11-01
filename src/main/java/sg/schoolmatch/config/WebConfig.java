package sg.schoolmatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.OriginCheckInterceptor;
import sg.schoolmatch.boundary.ui.support.VisitorLimitInterceptor;

/**
 * Registers the three interceptors. {@link VisitorLimitInterceptor} runs first, on every path: too many requests from
 * one address that use Google, OneMap or the sign-in forms get 429 (DC-90). {@link OriginCheckInterceptor} runs next,
 * on every path: a form post from another website gets 403 (DC-72). {@link AuthInterceptor} runs on the pages that
 * need login (FR-SHORTLIST-03, NFR-SEC-05, DC-07). {@code /schools/*}{@code /shortlist} is only protected for POST;
 * the interceptor checks the method itself.
 */
@Configuration
@Import(ClockConfig.class)   // the limit needs the Clock, and a @WebMvcTest loads this class but not ClockConfig
public class WebConfig implements WebMvcConfigurer {

    /** Paths that require an authenticated session. */
    public static final String[] LOGIN_REQUIRED_PATHS = {
            "/profile/**", "/shortlist/**", "/compare/**", "/plan/**", "/recommendations/**",
            "/logout", "/schools/*/shortlist"
    };

    private final VisitorLimitInterceptor visitorLimitInterceptor;
    private final OriginCheckInterceptor originCheckInterceptor;
    private final AuthInterceptor authInterceptor;

    public WebConfig(VisitorLimitInterceptor visitorLimitInterceptor, OriginCheckInterceptor originCheckInterceptor,
                     AuthInterceptor authInterceptor) {
        this.visitorLimitInterceptor = visitorLimitInterceptor;
        this.originCheckInterceptor = originCheckInterceptor;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitorLimitInterceptor).addPathPatterns("/**");
        registry.addInterceptor(originCheckInterceptor).addPathPatterns("/**");
        registry.addInterceptor(authInterceptor).addPathPatterns(LOGIN_REQUIRED_PATHS);
    }
}
