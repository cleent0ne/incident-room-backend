package com.incidentroom.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopologyNode {
    private String id;
    private String type;   // e.g. "service", "database", "cache", "queue", "load_balancer"
    private String label;
}
