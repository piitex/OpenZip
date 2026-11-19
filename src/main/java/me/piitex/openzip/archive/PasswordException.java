package me.piitex.openzip.archive;

import java.io.IOException;

/**
 * An archive needs a password, or the one given doesn't open it. The job stops: with a wrong password every entry
 * would fail the same way.
 */
public final class PasswordException extends IOException {
    private final boolean wrong;

    private PasswordException(String message, boolean wrong, Throwable cause) {
        super(message, cause);
        this.wrong = wrong;
    }

    /**
     * No password was given for an archive that needs one.
     */
    public static PasswordException required() {
        return new PasswordException("This archive is password protected", false, null);
    }

    /**
     * A password was given and it does not open the archive. For 7z this also covers a damaged archive.
     */
    public static PasswordException wrong(Throwable cause) {
        return new PasswordException("Wrong password", true, cause);
    }

    /**
     * 7z gives no way to tell a wrong password from damaged data, so the message says both.
     */
    public static PasswordException wrongOrDamaged(Throwable cause) {
        return new PasswordException("Wrong password, or the archive is damaged", true, cause);
    }

    public boolean isWrong() {
        return wrong;
    }
}
