package com.incidentroom.dto;

import com.incidentroom.domain.Topology;
import com.incidentroom.domain.TopologyEdge;
import com.incidentroom.domain.TopologyNode;

import java.util.List;

public record TopologyDto(List<NodeDto> nodes, List<EdgeDto> edges) {

    public record NodeDto(String id, String type, String label) {
        static NodeDto from(TopologyNode n) {
            return new NodeDto(n.getId(), n.getType(), n.getLabel());
        }
    }

    public record EdgeDto(String from, String to) {
        static EdgeDto from(TopologyEdge e) {
            return new EdgeDto(e.getFrom(), e.getTo());
        }
    }

    public static TopologyDto from(Topology topology) {
        if (topology == null) {
            return new TopologyDto(List.of(), List.of());
        }
        return new TopologyDto(
                topology.getNodes() == null ? List.of() : topology.getNodes().stream().map(NodeDto::from).toList(),
                topology.getEdges() == null ? List.of() : topology.getEdges().stream().map(EdgeDto::from).toList());
    }
}
