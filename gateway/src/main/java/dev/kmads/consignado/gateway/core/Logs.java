package dev.kmads.consignado.gateway.core;

import java.text.MessageFormat;

/** Log text is built here: the Functions log handler does not fill {0} placeholders. */
public final class Logs {
    private Logs() {}

    public static String fmt(String pattern, Object... args) {
        Object[] text = new Object[args.length];
        for (int i = 0; i < args.length; i++) text[i] = String.valueOf(args[i]);
        return MessageFormat.format(pattern, text);
    }

    /** Class and message of an exception, one line, at most 300 characters. Never log a token or a secret here. */
    public static String of(Throwable e) {
        String message = e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ");
        Throwable cause = e.getCause();
        String text = e.getClass().getName() + ": " + message
                + (cause == null || cause == e ? "" : " <- " + cause.getClass().getName());
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
