package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One investigative action taken during an attempt, in order — the audit trail of "what did the
 *  player check, and when" that both drives telemetry reveal and feeds the elapsed-time calculation. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttemptAction {
    private String actionKey;
    private int sequenceNo;
    private int takenAtOffsetSeconds;
}
