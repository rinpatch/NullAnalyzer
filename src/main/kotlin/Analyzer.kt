import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import java.io.File

private typealias AnalyzeIR = Pair<List<Analyzer.Error>, Map<String, Analyzer.Assumption>>
private typealias MethodMap = Map<String, List<Pair<String, Analyzer.Assumption>>>

object Analyzer {
    data class Error(val type: Type, val causedBy: Node, val proof: Assumption) {
        enum class Type { REDUNDANT_NULL_CHECK, REDUNDANT_NOT_NULL_CHECK, FIELD_ACCESS_MAY_BE_NULL, FIELD_ACCESS_IS_NULL, FUNCTION_CALL_MAY_BE_NULL, FUNCTION_CALL_IS_NULL }
    }

    data class Assumption(val type: Type, val causedBy: Node) {
        enum class Type { NOT_NULL, NULL, UNKNOWN, NULLABLE }
    }

    private fun analyze(startNode: Node, methodMap: MethodMap): List<Error> =
        analyze(startNode, methodMap, mapOf(), listOf()).first

    /// XXX: I realize visitor pattern would probably be preferred over simple recursive iteration, but I couldn't find a way to make it work for this use case.
    // TODO: See if there is a better way to operate on statements, rather than matching on their class and casting them.
    private fun analyze(
        startNode: Node,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        errors: List<Error>
    ): AnalyzeIR =
        // TODO: Switch statement
        when (startNode.javaClass) {
            MethodDeclaration::class.java -> analyze(startNode as MethodDeclaration, methodMap, assumptions, errors)
            BlockStmt::class.java -> analyze(startNode as BlockStmt, methodMap, assumptions, errors)
            ExpressionStmt::class.java -> analyze(startNode as ExpressionStmt, methodMap, assumptions, errors)
            IfStmt::class.java -> analyze(startNode as IfStmt, methodMap, assumptions, errors)
            else -> errors to assumptions
        }

    private fun analyze(
        methodDeclaration: MethodDeclaration,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        errors: List<Error>
    ): AnalyzeIR {
        val parameterAssumptions = methodMap[methodDeclaration.name.asString()]!!.toMap()
        val body = methodDeclaration.body.orElse(null) ?: return errors to assumptions
        return analyze(body, methodMap, assumptions + parameterAssumptions, errors)
    }

    private fun analyze(
        blockStatement: BlockStmt,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        errors: List<Error>
    ): AnalyzeIR {
        val (newErrors, _) = blockStatement.childNodes.fold(errors to assumptions) { (errors, assumptions), node ->
            analyze(
                node,
                methodMap,
                assumptions,
                errors
            )
        }
        return newErrors to assumptions
    }

    // TODO: Method map support
    private fun analyzeInitializer(
        expression: Expression?,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>
    ): Pair<Error?, Assumption.Type> {
        if (expression != null) {
            val unwrappedExpression = maybeUnwrapExpression(expression)
            return when {
                // XXX: Not correct in case of primitive types
                unwrappedExpression.isNullLiteralExpr -> null to Assumption.Type.NULL
                unwrappedExpression.isLiteralExpr -> null to Assumption.Type.NOT_NULL
                unwrappedExpression.isNameExpr -> {
                    val name = unwrappedExpression.asNameExpr().name.asString()
                    val assumptionType = when (val type = assumptions[name]?.type) {
                        null -> Assumption.Type.UNKNOWN
                        else -> type
                    }
                    null to assumptionType
                }
                unwrappedExpression.isFieldAccessExpr || unwrappedExpression.isMethodCallExpr -> {
                    val scope =
                        if (unwrappedExpression.isFieldAccessExpr) {
                            maybeUnwrapExpression(unwrappedExpression.asFieldAccessExpr().scope)
                        } else {
                            val wrappedScope = unwrappedExpression.asMethodCallExpr().scope.orElse(null)
                                ?: return null to Assumption.Type.UNKNOWN
                            maybeUnwrapExpression(wrappedScope)
                        }
                    val assumption = if (scope.isNameExpr) assumptions[scope.asNameExpr().name.asString()] else null
                    val error = when (assumption?.type) {
                        Assumption.Type.NULLABLE -> Error(Error.Type.FIELD_ACCESS_MAY_BE_NULL, expression, assumption)
                        Assumption.Type.NULL -> Error(Error.Type.FIELD_ACCESS_IS_NULL, expression, assumption)
                        else -> null
                    }
                    error to Assumption.Type.UNKNOWN
                }
                else -> null to Assumption.Type.UNKNOWN
            }
        } else {
            return null to Assumption.Type.UNKNOWN
        }
    }

    // TODO: Method map support
    private fun analyzeAssignExpr(assignExpression: AssignExpr, methodMap: MethodMap, assumptions: Map<String, Assumption>): AnalyzeIR =
        if (assignExpression.operator == AssignExpr.Operator.ASSIGN && assignExpression.target.isNameExpr) {
            val name = assignExpression.target.asNameExpr().name.asString()
            val (error, assumptionType) = analyzeInitializer(assignExpression.value, methodMap, assumptions)
            val assumption = Assumption(assumptionType, assignExpression)
            val errors = error?.let { listOf(error) } ?: listOf<Error>()
            errors to (assumptions + (name to assumption))
        } else {
            listOf<Error>() to assumptions
        }
    private fun analyzeExpression(expression: Expression, methodMap: MethodMap, assumptions: Map<String, Assumption>): Pair<Assumption.Type, AnalyzeIR> {
        val unwrappedExpression = maybeUnwrapExpression(expression)
        val unchangedIR = listOf<Error>() to assumptions
        return when (unwrappedExpression.javaClass) {
            AssignExpr::class.java -> analyzeExpression(expression.asAssignExpr(), methodMap, assumptions)
            NameExpr::class.java -> analyzeExpression(expression.asNameExpr(), methodMap, assumptions)
            FieldAccessExpr::class.java -> analyzeExpression(expression.asFieldAccessExpr(), methodMap, assumptions)
            MethodCallExpr::class.java -> analyzeExpression(expression.asMethodCallExpr(), methodMap, assumptions)
            NullLiteralExpr::class.java -> Assumption.Type.NULL to unchangedIR
            LiteralExpr::class.java -> Assumption.Type.NOT_NULL to unchangedIR
            else -> Assumption.Type.UNKNOWN to unchangedIR
        }
    }
    private fun analyzeExpression(assignExpression: AssignExpr, methodMap: MethodMap, assumptions: Map<String, Assumption>): Pair<Assumption.Type, AnalyzeIR> =
        if (assignExpression.operator == AssignExpr.Operator.ASSIGN) {
            val name = assignExpression.target.asNameExpr().name.asString()
            val (assumptionType, IR) = analyzeExpression(assignExpression.value, methodMap, assumptions)
            val newIR =
                if (!assignExpression.target.isNameExpr) IR
                else IR.first to IR.second + (assignExpression.target.asNameExpr().name.asString() to Assumption(assumptionType, assignExpression))
            assumptionType to newIR
        } else {
            Assumption.Type.UNKNOWN to (listOf<Error>() to assumptions)
        }

    private fun analyzeExpression(nameExpression: NameExpr, methodMap: MethodMap, assumptions: Map<String, Assumption>): Pair<Assumption.Type, AnalyzeIR> {
        val name = nameExpression.name.asString()
        val assumptionType = when (val type = assumptions[name]?.type) {
            null -> Assumption.Type.UNKNOWN
            else -> type
        }
        return assumptionType to (listOf<Error>() to assumptions)
    }
    private fun analyzeExpression(fieldAccessExpr: FieldAccessExpr, methodMap: MethodMap, assumptions: Map<String, Assumption>): Pair<Assumption.Type, AnalyzeIR> {
        val scope = maybeUnwrapExpression(fieldAccessExpr.scope)
        val assumption = if (scope.isNameExpr) assumptions[scope.asNameExpr().name.asString()] else null
        val error = when (assumption?.type) {
            Assumption.Type.NULLABLE -> Error(Error.Type.FIELD_ACCESS_MAY_BE_NULL, fieldAccessExpr, assumption)
            Assumption.Type.NULL -> Error(Error.Type.FIELD_ACCESS_IS_NULL, fieldAccessExpr, assumption)
            else -> null
        }
        val errors = error?.let { listOf(error) } ?: listOf<Error>()
        // TODO: Support fields in assumptions
        return Assumption.Type.NULLABLE to (errors to assumptions)
    }
    private fun analyzeExpression(methodCallExpr: MethodCallExpr, methodMap: MethodMap, assumptions: Map<String, Assumption>): Pair<Assumption.Type, AnalyzeIR> {
        if (methodCallExpr.scope.isEmpty) {
            val name = methodCallExpr.name.asString()
            val methodAssumptions = methodMap[name]
            if (methodAssumptions != null && methodAssumptions.size == methodCallExpr.arguments.size) {
                val argumentAssumptionTypes = methodCallExpr.arguments.map { analyzeExpression(it, methodMap, assumptions).first }
                val errors = methodAssumptions.withIndex().fold (listOf<Error>()){ errors, (index, parameter) ->
                   val (_, parameterAssumption)  = parameter
                    if(parameterAssumption.type == Assumption.Type.NOT_NULL && argumentAssumptionTypes[index] != Assumption.Type.NOT_NULL)  {
                        val error = when(argumentAssumptionTypes[index]) {
                            // TODO: proper assumption proof
                            Assumption.Type.NULL -> Error(Error.Type.FUNCTION_CALL_IS_NULL, methodCallExpr, Assumption(argumentAssumptionTypes[index], methodCallExpr))
                            else -> Error(Error.Type.FUNCTION_CALL_MAY_BE_NULL, methodCallExpr, Assumption(argumentAssumptionTypes[index], methodCallExpr))
                        }
                        errors + error
                    } else errors
                }
                return Assumption.Type.UNKNOWN to (errors to assumptions)
            }
        }
        // TODO: Field support
        else if (methodCallExpr.scope.isPresent && methodCallExpr.scope.get().isNameExpr) {
        }

        return Assumption.Type.UNKNOWN to (listOf<Error>() to assumptions)
    }

    private fun analyze(
        expressionStatement: ExpressionStmt,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        errors: List<Error>
    ): AnalyzeIR {
        val expression = expressionStatement.expression
        if (expression.isVariableDeclarationExpr) {
            val declarationExpression = expression.asVariableDeclarationExpr()
            val (newErrors, declarationAssumptions) = declarationExpression.variables.fold(listOf<Error>() to mapOf<String, Assumption>()) { acc, declarator ->
                val (accErrors, accAssumptions) = acc
                val name = declarator.name.asString()
                val initializer = declarator.initializer.orElse(null)
                // Adding accAssumptions should catch stuff like:
                // TreeNode test = null, test2 = test.getChildAt(0);
                val (error, assumptionType) = analyzeInitializer(initializer, methodMap, assumptions + accAssumptions)
                val assumption = Assumption(assumptionType, declarator)
                val newErrors = error?.let { listOf(error) } ?: listOf<Error>()
                (accErrors + newErrors) to (accAssumptions + (name to assumption))
            }
            return (errors + newErrors) to (declarationAssumptions + assumptions)
        } else  {
            val (_, IR) = analyzeExpression(expression, methodMap, assumptions)
            val (newErrors, newAssumptions) = IR
            return (errors + newErrors) to newAssumptions
        }
        return errors to assumptions
    }

    // IDK if it is normal for ASTs to have EnclosedExpression as a separate node, but seems really weird.
    private fun maybeUnwrapExpression(expression: Expression): Expression =
        if (expression.isEnclosedExpr) {
            maybeUnwrapExpression(expression.asEnclosedExpr().inner)
        } else {
            expression
        }

    private fun analyzeIfCondition(
        unaryExpr: UnaryExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): AnalyzeIR =
        if (unaryExpr.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            val unwrappedExpr = maybeUnwrapExpression(unaryExpr.expression)
            analyzeIfCondition(unwrappedExpr, methodMap, assumptions, !inversion)
        } else {
            listOf<Error>() to assumptions
        }

    // XXX: I am really not satisfied with the whole stupid when (x.javaClass) thing here and in analyze,
    // there must be a better way to do this. But I can't think of one.
    private fun analyzeIfCondition(
        expression: Expression,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): AnalyzeIR {
        val unwrappedExpr = maybeUnwrapExpression(expression)
        return when (unwrappedExpr.javaClass) {
            BinaryExpr::class.java -> analyzeIfCondition(unwrappedExpr.asBinaryExpr(), methodMap, assumptions, inversion)
            UnaryExpr::class.java -> analyzeIfCondition(unwrappedExpr.asUnaryExpr(), methodMap, assumptions, inversion)
            AssignExpr::class.java -> analyzeAssignExpr(unwrappedExpr.asAssignExpr(), methodMap, assumptions)
            else -> {
                ///XXX: Kind of stupid to have an if in a when, but it appears I can't do something like:
                // in listOf(MethodCallExpr::class.java, FieldAccessExpr::class.java) -> {
                if (unwrappedExpr.isMethodCallExpr || unwrappedExpr.isFieldAccessExpr) {
                    // XXX: Not really initalizer, but the function was already there and seems to accomplish the same goals
                    val (error, _) = analyzeInitializer(unwrappedExpr, methodMap, assumptions)
                    val errors = error?.let { listOf(error) } ?: listOf<Error>()
                    errors to assumptions
                } else {
                    listOf<Error>() to assumptions
                }
            }
        }
    }
// Redundant null check: Error if any of the expressions check for null when assumption is not null. For example ((test != null && cond == false) || cond2 == true)
// Assumption: Only assume the var is null or non-null if the condition is always checked.
// For example (((test != null) && cond1) || ((test != null) && cond2)) should result in test assumption being NOT_NULL

    private fun analyzeIfCondition(
        binaryExpr: BinaryExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): AnalyzeIR {
        val left = maybeUnwrapExpression(binaryExpr.left)
        val right = maybeUnwrapExpression(binaryExpr.right)
        when (val operator = binaryExpr.operator) {
            in listOf(BinaryExpr.Operator.AND, BinaryExpr.Operator.OR) -> {
                // De Morgan's laws
                val isOr = (operator == BinaryExpr.Operator.OR).xor(inversion)
                val (leftErrors, leftAssumptions) = analyzeIfCondition(left, methodMap, assumptions, inversion)
                // Pass left assumptions in case of &&, so that, for example @Nullable test would not produce
                // an error with if(test != null && test.field == "stuff")
                // TODO: Actually implement field checking in ifs
                val rightInputAssumptions = if (!isOr) leftAssumptions else assumptions
                val (rightErrors, rightAssumptions) = analyzeIfCondition(right, methodMap, rightInputAssumptions, inversion)
                val mergedAssumptions =
                    if (isOr) {
                        leftAssumptions.filter { (key, value) -> rightAssumptions[key] == value }
                    } else {
                        leftAssumptions + rightAssumptions
                    }
                return (rightErrors + leftErrors) to mergedAssumptions
            }

            in listOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS) -> {
                val isNotEquals = (operator == BinaryExpr.Operator.NOT_EQUALS).xor(inversion)
                val sides = listOf(left, right).map { maybeUnwrapExpression(it) }
                val (sideErrors, sideAssumptions) = sides.fold(listOf<Error>() to assumptions) { (errors, assumptions), side ->
                    when {
                        side.isNameExpr -> errors to assumptions
                        side.isLiteralExpr -> errors to assumptions
                        else -> {
                            val (addErrors, addAssumptions) = analyzeIfCondition(side, methodMap, assumptions, inversion)
                            (errors + addErrors) to (assumptions + addAssumptions)
                        }
                    }
                }
                val nameExpr = sides.find { it.isNameExpr }
                if (nameExpr != null) {
                    val name = nameExpr.asNameExpr().name.asString()
                    val second = (sides - nameExpr).first()
                    val (compareErrors, compareAssumptions) = when {
                        second.isNullLiteralExpr ->
                            when (sideAssumptions[name]?.type) {
                                Assumption.Type.NOT_NULL -> {
                                    val errorType = if (isNotEquals) {
                                        Error.Type.REDUNDANT_NOT_NULL_CHECK
                                    } else {
                                        Error.Type.REDUNDANT_NULL_CHECK
                                    }
                                    listOf(
                                        Error(
                                            errorType,
                                            binaryExpr,
                                            sideAssumptions[name]!!
                                        )
                                    ) to mapOf<String, Assumption>()
                                }

                                in listOf(Assumption.Type.UNKNOWN, Assumption.Type.NULLABLE) -> {
                                    val assumptionType = if (isNotEquals) {
                                        Assumption.Type.NOT_NULL
                                    } else {
                                        Assumption.Type.NULL
                                    }
                                    listOf<Error>() to mapOf(
                                        name to Assumption(
                                            assumptionType,
                                            binaryExpr
                                        )
                                    )
                                }
                                else -> {
                                    listOf<Error>() to mapOf()
                                }
                            }
                        second.isLiteralExpr ->
                            if (!inversion) {
                                listOf<Error>() to sideAssumptions + mapOf(
                                    name to Assumption(
                                        Assumption.Type.NOT_NULL,
                                        binaryExpr
                                    )
                                )} else {
                                    listOf<Error>() to mapOf<String, Assumption>()
                                }
                        second.isNameExpr -> {
                            // FIXME: Does behave correctly with != and inversions. Create test cases and fix.
                            // TODO: Think whether these !! can fail on valid code
                            val firstAssumptionType = sideAssumptions[name]!!.type
                            val secondName = second.asNameExpr().name.asString()
                            val secondAssumptionType = sideAssumptions[secondName]!!.type
                            val (error, newAssumptions) = when {
                                firstAssumptionType in listOf(
                                    Assumption.Type.UNKNOWN,
                                    Assumption.Type.NULLABLE
                                ) && secondAssumptionType != Assumption.Type.UNKNOWN && !isNotEquals ->
                                    null to mapOf(name to Assumption(secondAssumptionType, binaryExpr))
                                secondAssumptionType in listOf(
                                    Assumption.Type.UNKNOWN,
                                    Assumption.Type.NULLABLE
                                ) && firstAssumptionType != Assumption.Type.UNKNOWN && !isNotEquals ->
                                    null to mapOf(secondName to Assumption(firstAssumptionType, binaryExpr))
                                firstAssumptionType == Assumption.Type.NULL && secondAssumptionType == Assumption.Type.NOT_NULL ->
                                    // TODO: Maybe proof should include two assumptions for this case
                                    Error(
                                        if (isNotEquals) {
                                            Error.Type.REDUNDANT_NOT_NULL_CHECK
                                        } else {
                                            Error.Type.REDUNDANT_NULL_CHECK
                                        },
                                        binaryExpr,
                                        assumptions[secondName]!!
                                    ) to mapOf()
                                secondAssumptionType == Assumption.Type.NULL && firstAssumptionType == Assumption.Type.NOT_NULL ->
                                    Error(
                                        if (isNotEquals) {
                                            Error.Type.REDUNDANT_NOT_NULL_CHECK
                                        } else {
                                            Error.Type.REDUNDANT_NULL_CHECK
                                        }, binaryExpr, assumptions[name]!!
                                    ) to mapOf()
                                else -> null to mapOf<String, Assumption>()
                            }
                            val errorList = if (error != null) listOf(error) else listOf()
                            errorList to newAssumptions
                        }
                        else -> listOf<Error>() to mapOf<String, Assumption>()
                    }
                    return compareErrors + sideErrors to sideAssumptions + compareAssumptions
                } else {
                    return sideErrors to sideAssumptions
                }
            }
        }
        return listOf<Error>() to assumptions
    }

    private fun analyze(
        ifStatement: IfStmt,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        errors: List<Error>
    ): AnalyzeIR {
        val (conditionErrors, newAssumptions) = analyzeIfCondition(ifStatement.condition, methodMap, assumptions, false)

        val (ifErrors, _) = analyze(ifStatement.thenStmt, methodMap, newAssumptions, errors + conditionErrors)

        val (newErrors, _) =
            if (ifStatement.elseStmt.isPresent) {
                analyze(ifStatement.elseStmt.get(), methodMap, assumptions, ifErrors)
            } else {
                ifErrors to assumptions
            }
        return newErrors to assumptions
    }

    private fun buildMethodMap(cu: CompilationUnit): MethodMap =
        cu.findAll(MethodDeclaration::class.java).associate { methodDeclaration ->
            val parameterAssumptions = methodDeclaration.parameters.map { parameter ->
                val annotation = parameter.annotations.find { it.name.asString() in listOf("Nullable", "NotNull") }
                val assumptionType = when {
                    annotation == null -> Assumption.Type.UNKNOWN
                    annotation.nameAsString == "Nullable" -> Assumption.Type.NULLABLE
                    else -> Assumption.Type.NOT_NULL
                }
                parameter.nameAsString to Assumption(assumptionType, parameter)
            }
            methodDeclaration.name.asString() to parameterAssumptions
        }

    fun analyze(cu: CompilationUnit): List<Error> {
        val methodMap = buildMethodMap(cu)
        return cu.findAll(MethodDeclaration::class.java).fold(listOf()) { acc, method ->
            println("${method.nameAsString} ${analyze(method, methodMap)}")
            acc + analyze(method, methodMap)
        }
    }

    fun parse(file: File): CompilationUnit {
//        val parserConfig = ParserConfiguration().setAttributeComments(true)
//        StaticJavaParser.setConfiguration(parserConfig)
        return StaticJavaParser.parse(file)!!
    }

    fun parseAndAnalyze(file: File): List<Error> =
        analyze(parse(file))
}
