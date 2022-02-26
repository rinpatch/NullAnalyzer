import java.io.File

fun main(args: Array<String>) {
    val file = File(args[0])
    val result = Analyzer.parseAndAnalyze(file)
    println(result)
    //    JavaParserJsonSerializer().serialize(node, javax.json.Json.createGenerator(FileOutputStream(File("out.json"))))
}
