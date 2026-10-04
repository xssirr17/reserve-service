package io.github.xssirr17.reserve.common.error;

public class InvalidStateTransitionException extends ConflictException {

    private final String currentStatus;
    private final String targetStatus;

    public InvalidStateTransitionException(String message) {
        super(message);
        this.currentStatus = null;
        this.targetStatus = null;
    }

    public InvalidStateTransitionException(Object currentStatus, Object targetStatus) {
        super(String.format("Cannot transition reservation from %s to %s", currentStatus, targetStatus));
        this.currentStatus = currentStatus != null ? currentStatus.toString() : null;
        this.targetStatus = targetStatus != null ? targetStatus.toString() : null;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public String getTargetStatus() {
        return targetStatus;
    }
}
