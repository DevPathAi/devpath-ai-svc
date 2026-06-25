package ai.devpath.aigw.mentor;

import java.util.List;

public record SimilarQuery(List<Double> embedding, Integer limit, String track) {}
