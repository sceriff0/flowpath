package qupath.ext.flowpath.model;

/** A candidate phenotype for a cell, with its softmax pheno_score. */
public record Candidate(String name, double score) {}
