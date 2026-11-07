; Kotlin indentation aligned with the Java indents query.

; format-ignore
[
  (function_body)
  (class_body)
  (enum_class_body)
  (control_structure_body)
  (when_expression)
  (lambda_literal)
  (collection_literal)
  (value_arguments)
  (function_value_parameters)
  (type_parameters)
  (type_arguments)
  (catch_block)
] @indent.begin

[
  "("
  ")"
  "{"
  "}"
  "["
  "]"
] @indent.branch

"}" @indent.end
