# Change Log

	2.0.17 - Fixed some type-hints. Fixed bug with href-for which
	ignored the :query-params in the options map. This may break
	usages which workaround this bug resulting in duplicate
	query-strings in the same URI.

	2.0.12 - Replaced :uri-for with :uri-info in vhosts handler - breaking change
