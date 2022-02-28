import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
@SerialName("Analyzer.Error")
private class ErrorSurrogate(val line: Int, val type: String, val offendingCode: String)

object ErrorSerializer: KSerializer<Analyzer.Error> {
    override val descriptor: SerialDescriptor = ErrorSurrogate.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Analyzer.Error) {
        val line = value.causedBy.range.get().begin.line
        encoder.encodeSerializableValue(ErrorSurrogate.serializer(), ErrorSurrogate(line, value.type.toString(), value.causedBy.toString()))
    }

    override fun deserialize(decoder: Decoder): Analyzer.Error {
        TODO("Not implemented")
    }
}