package id.fatarc.portfolio.payflowreliability.common;

/**
 * Thrown by domain objects when a requested lifecycle transition is not allowed from
 * the current state. Maps to 409 at the API boundary.
 */
public class InvalidStateTransitionException extends BusinessException {
    public InvalidStateTransitionException(String message) {
        super(ErrorCode.INVALID_STATE_TRANSITION, message);
    }
}
