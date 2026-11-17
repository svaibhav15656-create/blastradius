package blastradius.models;

/** How serious a permission request is. Drives the popup header colour. */
public enum Severity {
    /** Red: could cause serious or hard-to-reverse harm. */
    CRITICAL,
    /** Yellow: worth a closer look before allowing. */
    WARNING,
    /** Green: no known risks found. */
    SAFE
}
