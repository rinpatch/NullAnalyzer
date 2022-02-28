import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size == 2) {
        val input = File(args[0])
        val output = File(args[1])
        val errors = Analyzer.parseAndAnalyze(input)
        output.writeText(Json.encodeToString(errors))
    } else {
        println("Usage: INPUT_FILE OUTPUT_FILE")
        exitProcess(1)
    }
    //    JavaParserJsonSerializer().serialize(node, javax.json.Json.createGenerator(FileOutputStream(File("out.json"))))
}
