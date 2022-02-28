import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.SwitchStmt
import kotlinx.serialization.Serializable
import java.io.File

private typealias AnalyzeIR = Pair<List<Analyzer.Error>, Map<String, Analyzer.Assumption>>
private typealias MethodMap = Map<String, List<Pair<String, Analyzer.Assumption>>>

object Analyzer {
    @Serializable(with = ErrorSerializer::class)
    data class Error(val type: Type, val causedBy: Node, val proof: Assumption) {
        enum class Type { REDUNDANT_NULL_CHECK, REDUNDANT_NOT_NULL_CHECK, FIELD_ACCESS_MAY_BE_NULL, FIELD_ACCESS_IS_NULL, FUNCTION_CALL_MAY_BE_NULL, FUNCTION_CALL_IS_NULL, SWITCH_ON_NULL }
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
        when (startNode.javaClass) {
            MethodDeclaration::class.java -> analyze(startNode as MethodDeclaration, methodMap, assumptions, errors)
            BlockStmt::class.java -> analyze(startNode as BlockStmt, methodMap, assumptions, errors)
            ExpressionStmt::class.java -> analyze(startNode as ExpressionStmt, methodMap, assumptions, errors)
            IfStmt::class.java -> analyze(startNode as IfStmt, methodMap, assumptions, errors)
            SwitchStmt::class.java -> analyze(startNode as SwitchStmt, methodMap, assumptions, errors)
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

    private fun analyzeExpression(
        expression: Expression,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): Pair<Assumption, AnalyzeIR> {
        val unwrappedExpression = maybeUnwrapExpression(expression)
        val unchangedIR = listOf<Error>() to assumptions
        return when (unwrappedExpression.javaClass) {
            VariableDeclarationExpr::class.java -> analyzeExpression(
                expression.asVariableDeclarationExpr(),
                methodMap,
                assumptions,
                inversion
            )
            AssignExpr::class.java -> analyzeExpression(expression.asAssignExpr(), methodMap, assumptions, inversion)
            NameExpr::class.java -> analyzeExpression(expression.asNameExpr(), methodMap, assumptions, inversion)
            FieldAccessExpr::class.java -> analyzeExpression(
                expression.asFieldAccessExpr(),
                methodMap,
                assumptions,
                inversion
            )
            MethodCallExpr::class.java -> analyzeExpression(
                expression.asMethodCallExpr(),
                methodMap,
                assumptions,
                inversion
            )
            UnaryExpr::class.java -> analyzeExpression(expression.asUnaryExpr(), methodMap, assumptions, inversion)
            BinaryExpr::class.java -> analyzeExpression(expression.asBinaryExpr(), methodMap, assumptions, inversion)
            NullLiteralExpr::class.java -> Assumption(Assumption.Type.NULL, unwrappedExpression) to unchangedIR
            else -> {
                val assumptionType =
                    if (unwrappedExpression.isLiteralExpr) Assumption.Type.NOT_NULL
                    else Assumption.Type.UNKNOWN
                return Assumption(assumptionType, unwrappedExpression) to unchangedIR
            }
        }
    }

    private fun analyzeExpression(
        declarationExpression: VariableDeclarationExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): Pair<Assumption, AnalyzeIR> {
        val IR = declarationExpression.variables.fold(listOf<Error>() to assumptions) { acc, declarator ->
            val (accErrors, accAssumptions) = acc
            val name = declarator.name.asString()
            val initializer = declarator.initializer.orElse(null)
            // Adding accAssumptions should catch stuff like:
            // TreeNode test = null, test2 = test.getChildAt(0);
            val (assumption, IR) = analyzeExpression(initializer, methodMap, accAssumptions, inversion)
            val (initializerErrors, newAssumptions) = IR
            (accErrors + initializerErrors) to (newAssumptions + (name to assumption))
        }
        return Assumption(Assumption.Type.UNKNOWN, declarationExpression) to IR
    }

    private fun analyzeExpression(
        assignExpression: AssignExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): Pair<Assumption, AnalyzeIR> =
        if (assignExpression.operator == AssignExpr.Operator.ASSIGN) {
            val name = assignExpression.target.asNameExpr().name.asString()
            val (assumption, IR) = analyzeExpression(assignExpression.value, methodMap, assumptions, inversion)
            val newIR =
                if (!assignExpression.target.isNameExpr) IR
                else IR.first to IR.second + (name to assumption)
            assumption to newIR
        } else {
            Assumption(Assumption.Type.UNKNOWN, assignExpression) to (listOf<Error>() to assumptions)
        }

    @Suppress("UNUSED_PARAMETER")
    private fun analyzeExpression(
        nameExpression: NameExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): Pair<Assumption, AnalyzeIR> {
        val name = nameExpression.name.asString()
        val assumption = assumptions[name] ?: Assumption(Assumption.Type.UNKNOWN, nameExpression)
        return assumption to (listOf<Error>() to assumptions)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun analyzeExpression(
        fieldAccessExpr: FieldAccessExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): Pair<Assumption, AnalyzeIR> {
        val scope = maybeUnwrapExpression(fieldAccessExpr.scope)
        val assumption = if (scope.isNameExpr) assumptions[scope.asNameExpr().name.asString()] else null
        val error = when (assumption?.type) {
            Assumption.Type.NULLABLE -> Error(Error.Type.FIELD_ACCESS_MAY_BE_NULL, fieldAccessExpr, assumption)
            Assumption.Type.NULL -> Error(Error.Type.FIELD_ACCESS_IS_NULL, fieldAccessExpr, assumption)
            else -> null
        }
        val errors = error?.let { listOf(error) } ?: listOf()
        // Will cause errors since we don't really support fields in assumptions
        // return Assumption(Assumption.Type.NULLABLE, fieldAccessExpr) to (errors to assumptions)
         return Assumption(Assumption.Type.UNKNOWN, fieldAccessExpr) to (errors to assumptions)
    }

    private fun analyzeExpression(
        methodCallExpr: MethodCallExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): Pair<Assumption, AnalyzeIR> {
        val returnAssumption = Assumption(Assumption.Type.UNKNOWN, methodCallExpr)
        if (methodCallExpr.scope.isEmpty) {
            val name = methodCallExpr.name.asString()
            val methodAssumptions = methodMap[name]
            if (methodAssumptions != null && methodAssumptions.size == methodCallExpr.arguments.size) {
                val argumentAssumptions =
                    methodCallExpr.arguments.map { analyzeExpression(it, methodMap, assumptions, inversion).first }
                val errors = methodAssumptions.withIndex().fold(listOf<Error>()) { errors, (index, parameter) ->
                    val (_, parameterAssumption) = parameter
                    if (parameterAssumption.type == Assumption.Type.NOT_NULL && argumentAssumptions[index].type != Assumption.Type.NOT_NULL) {
                        val error = when (argumentAssumptions[index].type) {
                            Assumption.Type.NULL -> Error(
                                Error.Type.FUNCTION_CALL_IS_NULL,
                                methodCallExpr,
                                argumentAssumptions[index]
                            )
                            else -> Error(
                                Error.Type.FUNCTION_CALL_MAY_BE_NULL,
                                methodCallExpr,
                                argumentAssumptions[index]
                            )
                        }
                        errors + error
                    } else errors
                }
                return returnAssumption to (errors to assumptions)
            }
        }
        // TODO: Field support
        else if (methodCallExpr.scope.isPresent && methodCallExpr.scope.get().isNameExpr) {
            val assumption = assumptions[methodCallExpr.scope.get().asNameExpr().name.asString()]
            val error = when (assumption?.type) {
                Assumption.Type.NULL -> Error(Error.Type.FIELD_ACCESS_IS_NULL, methodCallExpr, assumption)
                Assumption.Type.NULLABLE -> Error(Error.Type.FIELD_ACCESS_MAY_BE_NULL, methodCallExpr, assumption)
                else -> null
            }
            val errors = error?.let { listOf(it) } ?: listOf()
            return returnAssumption to (errors to assumptions)
        }

        return returnAssumption to (listOf<Error>() to assumptions)
    }

    private fun analyze(
        expressionStatement: ExpressionStmt,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        errors: List<Error>
    ): AnalyzeIR {
        val expression = expressionStatement.expression
        val (_, IR) = analyzeExpression(expression, methodMap, assumptions, false)
        val (newErrors, newAssumptions) = IR
        return (errors + newErrors) to newAssumptions
    }

    // IDK if it is normal for ASTs to have EnclosedExpression as a separate node, but seems really weird.
    private fun maybeUnwrapExpression(expression: Expression): Expression =
        if (expression.isEnclosedExpr) {
            maybeUnwrapExpression(expression.asEnclosedExpr().inner)
        } else {
            expression
        }

    private fun analyzeExpression(
        unaryExpr: UnaryExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): Pair<Assumption, AnalyzeIR> =
        if (unaryExpr.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            val unwrappedExpr = maybeUnwrapExpression(unaryExpr.expression)
            analyzeExpression(unwrappedExpr, methodMap, assumptions, !inversion)
        } else {
            Assumption(Assumption.Type.UNKNOWN, unaryExpr) to (listOf<Error>() to assumptions)
        }

    private fun maybeInvertAssumptionType(assumptionType: Assumption.Type, invert: Boolean): Assumption.Type =
        if (invert) {
            when (assumptionType) {
                Assumption.Type.NULL -> Assumption.Type.NOT_NULL
                else -> Assumption.Type.UNKNOWN
            }
        } else assumptionType

    private fun analyzeExpression(
        binaryExpr: BinaryExpr,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        inversion: Boolean
    ): Pair<Assumption, AnalyzeIR> {
        val left = maybeUnwrapExpression(binaryExpr.left)
        val right = maybeUnwrapExpression(binaryExpr.right)
        return when (val operator = binaryExpr.operator) {
            in listOf(BinaryExpr.Operator.AND, BinaryExpr.Operator.OR) -> {
                // De Morgan's laws
                val isOr = (operator == BinaryExpr.Operator.OR).xor(inversion)
                val (_, leftIR) = analyzeExpression(left, methodMap, assumptions, inversion)
                val (leftErrors, leftAssumptions) = leftIR
                // Pass left assumptions in case of &&, so that, for example @Nullable test would not produce
                // an error with if(test != null && test.field == "stuff")
                val rightInputAssumptions = if (!isOr) leftAssumptions else assumptions
                val (_, rightIR) = analyzeExpression(right, methodMap, rightInputAssumptions, inversion)
                val (rightErrors, rightAssumptions) = rightIR
                val mergedAssumptions =
                    if (isOr) {
                        leftAssumptions.filter { (key, value) -> rightAssumptions[key] == value }
                    } else {
                        leftAssumptions + rightAssumptions
                    }
                Assumption(Assumption.Type.UNKNOWN, binaryExpr) to ((rightErrors + leftErrors) to mergedAssumptions)
            }

            in listOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS) -> {
                val isNotEquals = (operator == BinaryExpr.Operator.NOT_EQUALS).xor(inversion)
                val (leftAssumption, leftIR) = analyzeExpression(left, methodMap, assumptions, inversion)
                val (rightAssumption, rightIR) = analyzeExpression(right, methodMap, assumptions, inversion)
                val sideErrors = leftIR.first + rightIR.first
                val (error, assumptionEntry) = when {
                    leftAssumption.type == Assumption.Type.NULL && rightAssumption.type == Assumption.Type.NULL -> {
                        val errorType =
                            if (isNotEquals) Error.Type.REDUNDANT_NOT_NULL_CHECK else Error.Type.REDUNDANT_NULL_CHECK
                        Error(errorType, binaryExpr, leftAssumption) to null
                    }
                    leftAssumption.type == Assumption.Type.NOT_NULL && rightAssumption.type == Assumption.Type.NULL -> {
                        val errorType =
                            if (isNotEquals) Error.Type.REDUNDANT_NOT_NULL_CHECK else Error.Type.REDUNDANT_NULL_CHECK
                        Error(errorType, binaryExpr, leftAssumption) to null
                    }
                    leftAssumption.type == Assumption.Type.NULL && rightAssumption.type == Assumption.Type.NOT_NULL -> {
                        val errorType =
                            if (isNotEquals) Error.Type.REDUNDANT_NULL_CHECK else Error.Type.REDUNDANT_NOT_NULL_CHECK
                        Error(errorType, binaryExpr, leftAssumption) to null
                    }
                    leftAssumption.type == Assumption.Type.NULLABLE && rightAssumption.type == Assumption.Type.NULLABLE -> null to null
                    // Transfer assumption if name/fieldexpr
                    leftAssumption.type in listOf(
                        Assumption.Type.UNKNOWN,
                        Assumption.Type.NULLABLE
                    ) && rightAssumption.type != Assumption.Type.UNKNOWN -> {
                        // TODO: Field support
                        if (left.isNameExpr) {
                            null to (left.asNameExpr().name.asString() to Assumption(
                                maybeInvertAssumptionType(
                                    rightAssumption.type,
                                    isNotEquals
                                ), rightAssumption.causedBy
                            ))
                        } else null to null
                    }
                    rightAssumption.type in listOf(
                        Assumption.Type.UNKNOWN,
                        Assumption.Type.NULLABLE
                    ) && leftAssumption.type != Assumption.Type.UNKNOWN -> {
                        if (right.isNameExpr) {
                            null to (right.asNameExpr().name.asString() to Assumption(
                                maybeInvertAssumptionType(
                                    leftAssumption.type,
                                    isNotEquals
                                ), leftAssumption.causedBy
                            ))
                        } else null to null
                    }
                    else -> null to null
                }
                val errors = error?.let { sideErrors + it } ?: sideErrors
                val newAssumptions = assumptionEntry?.let { assumptions + it } ?: assumptions
                Assumption(Assumption.Type.UNKNOWN, binaryExpr) to (errors to newAssumptions)
            }
            else -> {
                val (_, leftIR) = analyzeExpression(left, methodMap, assumptions, inversion)
                val (_, rightIR) = analyzeExpression(right, methodMap, assumptions, inversion)
                Assumption(Assumption.Type.UNKNOWN, binaryExpr) to ((leftIR.first + rightIR.first) to assumptions)
            }
        }
    }

    private fun analyze(
        ifStatement: IfStmt,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        errors: List<Error>
    ): AnalyzeIR {
        val (_, IR) = analyzeExpression(ifStatement.condition, methodMap, assumptions, false)
        val (conditionErrors, newAssumptions) = IR
        val elseAssumptions =
            newAssumptions
                .filterNot { (key, value) -> assumptions[key] == value }
                .filterValues { it.type in listOf(Assumption.Type.NULL, Assumption.Type.NOT_NULL) }
                .mapValues {
                    when (it.value.type) {
                        Assumption.Type.NULL -> Assumption(Assumption.Type.NOT_NULL, it.value.causedBy)
                        else -> Assumption(Assumption.Type.NULL, it.value.causedBy)
                    }
                }

        val (ifErrors, _) = analyze(ifStatement.thenStmt, methodMap, newAssumptions, errors + conditionErrors)

        val (newErrors, _) =
            if (ifStatement.elseStmt.isPresent) {
                analyze(ifStatement.elseStmt.get(), methodMap, assumptions + elseAssumptions, ifErrors)
            } else {
                ifErrors to assumptions
            }
        return newErrors to assumptions
    }

    private fun analyze(
        switchStatement: SwitchStmt,
        methodMap: MethodMap,
        assumptions: Map<String, Assumption>,
        errors: List<Error>
    ): AnalyzeIR {
        val (assumption, _) = analyzeExpression(switchStatement.selector, methodMap, assumptions, false)
        val newErrors = if (assumption.type == Assumption.Type.NULL) {
            errors + Error(Error.Type.SWITCH_ON_NULL, switchStatement.selector, assumption)
        } else {
            errors
        }

        return switchStatement.entries.fold(newErrors to assumptions) { (errors, assumptions), entryStatement ->
            entryStatement.statements.fold(errors to assumptions) { (errors, assumptions), statement ->
                analyze(statement, methodMap, assumptions, errors)
            }
        }
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
            acc + analyze(method, methodMap)
        }
    }

    fun parse(file: File): CompilationUnit = StaticJavaParser.parse(file)!!

    fun parseAndAnalyze(file: File): List<Error> =
        analyze(parse(file))
}
