package sg.schoolmatch.error;

import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * The one WARN line a page or control writes when a Google or OneMap failure becomes a "temporarily unavailable"
 * message (NFR-MAIN-02, NFR-USE-03): {@code "<feature> unavailable: <service>: <what happened>"}.
 * <p>
 * What happened comes from the exception's cause. A cause marked {@link Described} already says it safely; the
 * live Google clients and the daily budget use these, so their lines read:
 * <ul>
 *   <li>{@code app daily limit reached for <sku> (…)}: the app's own limit (ExternalCallBudget); nothing was sent.</li>
 *   <li>{@code Google refused the request: HTTP 403, PERMISSION_DENIED, <reason>: <Google's message>}: Google answered
 *       4xx (key, API restriction, billing, Google's daily quota).</li>
 *   <li>{@code Google failed: HTTP 5xx} or {@code Google failed: no answer (…)}: Google's error, a timeout or a
 *       network failure.</li>
 * </ul>
 * Any other cause gives {@code <service> failed: <exception class>: <first part of its message>} plus the root
 * cause. The line never holds a key, a request header or a full URL: URLs are cut to scheme and host, and anything
 * that looks like a Google key or a {@code key=} parameter is removed. No stack trace is written.
 */
public final class ExternalFailureLog {

    /** A cause whose {@code getMessage()} is a complete one-line description that is safe to log. */
    public interface Described {
    }

    private static final int MAX_TEXT = 400;
    private static final Pattern URL = Pattern.compile("(https?://[^/\\s\"'?#]+)[^\\s\"']*");
    private static final Pattern GOOGLE_KEY = Pattern.compile("AIza[0-9A-Za-z_\\-]{10,}");
    private static final Pattern KEY_PARAMETER =
            Pattern.compile("(?i)\\b(key|api_?key|token|access_token|signature)=[^&\\s\"']*");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");

    private ExternalFailureLog() {
    }

    /** Writes {@code "<feature> unavailable: <describe(e)>"} as one WARN line, without a stack trace. */
    public static void warn(Logger log, String feature, ExternalServiceUnavailableException e) {
        log.warn("{} unavailable: {}", feature, describe(e));
    }

    /** {@code "<service>: <what happened>"}; see the class comment. */
    public static String describe(ExternalServiceUnavailableException e) {
        String service = e.getService() == null ? "External service" : e.getService();
        Throwable cause = e.getCause();
        if (cause instanceof Described && cause.getMessage() != null) {
            return service + ": " + clean(cause.getMessage(), MAX_TEXT);
        }
        String provider = service.startsWith("Google") ? "Google" : service;
        if (cause == null) {
            return service + ": " + provider + " failed (no further detail)";
        }
        StringBuilder line = new StringBuilder(service).append(": ").append(provider).append(" failed: ")
                .append(nameAndFirstClause(cause));
        Throwable root = rootCause(cause);
        if (root != cause) {
            line.append("; ").append(nameAndFirstClause(root));
        }
        return line.toString();
    }

    /**
     * {@code text} as one line with no key, URLs cut to scheme and host, at most {@code max} characters
     * (for the {@link Described} messages too).
     */
    public static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = CONTROL.matcher(text).replaceAll(" ").strip();
        s = URL.matcher(s).replaceAll("$1");
        s = GOOGLE_KEY.matcher(s).replaceAll("[key removed]");
        s = KEY_PARAMETER.matcher(s).replaceAll("$1=[removed]");
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 10; depth++) {
            root = root.getCause();
        }
        return root;
    }

    /** "HttpServerErrorException$ServiceUnavailable: 503 Service Unavailable" (the part before the first ": "). */
    private static String nameAndFirstClause(Throwable t) {
        String name = t.getClass().getName();
        name = name.substring(name.lastIndexOf('.') + 1);
        String message = t.getMessage() == null ? "" : t.getMessage();
        int end = message.indexOf(": ");
        String first = clean(end < 0 ? message : message.substring(0, end), 160);
        return first.isEmpty() ? name : name + ": " + first;
    }
}
