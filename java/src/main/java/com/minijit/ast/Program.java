package com.minijit.ast;

import java.util.List;

/** Root of a parsed MiniJIT source file: a flat list of function declarations. */
public record Program(List<Stmt.Function> functions) {}
