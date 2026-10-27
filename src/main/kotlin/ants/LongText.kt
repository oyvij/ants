package ants

import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.operaton.bpm.engine.impl.variable.serializer.StringValueSerializer
import org.operaton.bpm.engine.impl.variable.serializer.ValueFields
import org.operaton.bpm.engine.variable.Variables
import org.operaton.bpm.engine.variable.value.StringValue
import org.operaton.bpm.engine.variable.value.TypedValue
import org.springframework.stereotype.Component

/**
 * Keeps a string variable longer than Operaton's text column ([MAX] characters) as bytes, so a long prompt, instruction
 * or comment is stored rather than refused with "Variable value is too long". It reads back as the same string; a
 * shorter one is stored as text, as before.
 */
@Component
class LongText : AbstractProcessEnginePlugin() {
    override fun preInit(configuration: ProcessEngineConfigurationImpl) {
        configuration.customPreVariableSerializers = (configuration.customPreVariableSerializers ?: mutableListOf()) + Serializer()
    }

    /** Operaton's string serializer under its own name, taking only the strings too long for text. */
    class Serializer : StringValueSerializer() {
        override fun getName() = NAME

        override fun canWriteValue(value: TypedValue?) = super.canWriteValue(value) && "${value?.value ?: ""}".length > MAX

        override fun writeValue(value: StringValue, fields: ValueFields) = fields.setByteArrayValue(value.value!!.toByteArray())

        override fun readValue(fields: ValueFields, asTransientValue: Boolean): StringValue =
            Variables.stringValue(fields.byteArrayValue?.toString(Charsets.UTF_8), asTransientValue)
    }

    companion object {
        /** The type name a long string is stored under. */
        const val NAME = "longstring"

        /** The longest string Operaton keeps as text. */
        const val MAX = 4000
    }
}
