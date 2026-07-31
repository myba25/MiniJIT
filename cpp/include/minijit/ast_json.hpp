#pragma once

#include "minijit/ast.hpp"

#include <stdexcept>
#include <string>

namespace minijit {

/// Thrown when the incoming JSON does not match the expected AST schema.
class AstJsonError : public std::runtime_error {
public:
    explicit AstJsonError(const std::string& message)
        : std::runtime_error("AST JSON error: " + message) {}
};

/// Parses the JSON produced by the Java frontend (com.minijit.ast.AstJson)
/// into a C++ Program. Throws AstJsonError on malformed input.
Program parseProgramJson(const std::string& json);

} // namespace minijit
