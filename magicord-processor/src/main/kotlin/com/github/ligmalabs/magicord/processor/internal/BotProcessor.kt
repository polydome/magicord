package com.github.ligmalabs.magicord.processor.internal

import com.github.ligmalabs.magicord.annotation.Bot
import com.github.ligmalabs.magicord.annotation.PrefixCommand
import com.github.ligmalabs.magicord.api.BotConfig
import com.github.ligmalabs.magicord.processor.ClassProcessor
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.*
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.event.message.MessageCreateEvent

internal class BotProcessor(private val prefixCommandProcessor: PrefixCommandProcessor, private val codeGenerator: CodeGenerator) :
    ClassProcessor {
    override fun process(classDeclaration: KSClassDeclaration) {
        val packageName = classDeclaration.containingFile!!.packageName.asString()
        val className = "Magicord${classDeclaration.simpleName.asString()}"

        val botType = buildBot(classDeclaration)

        FileSpec.builder(packageName, className)
            .addType(botType)
            .build()
            .writeTo(codeGenerator, Dependencies(true, classDeclaration.containingFile!!))
    }

    private fun buildBot(classDeclaration: KSClassDeclaration): TypeSpec {
        val prefixCommandHandlers = classDeclaration.getDeclaredFunctions()
            .filter { it.isAnnotatedWith(PrefixCommand::class) }
            .map { prefixCommandProcessor.buildHandler(it) }

        val onMessageFun = createOnMessageFun(prefixCommandHandlers)

        return TypeSpec.classBuilder("Magicord${classDeclaration.simpleName.asString()}")
            .addProperty(createBotInstanceProperty(classDeclaration.guessClass()))
            .addFunction(createRunFun(classDeclaration.readBotConfig(), onMessageFun))
            .addFunctions(prefixCommandHandlers.asIterable())
            .addFunction(onMessageFun)
            .build()
    }

    private fun KSClassDeclaration.readBotConfig(): BotConfig {
        val botAnnotation = annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == Bot::class.qualifiedName
        }

        val token = botAnnotation.arguments.firstOrNull { it.name?.asString() == "token" }?.value.toString()

        return BotConfig(
            token = token
        )
    }

    private fun createRunFun(botConfig: BotConfig, onMessageFun: FunSpec): FunSpec {
        val runBuilder = FunSpec.builder("run")
            .addStatement("println(%S)", "Running a bot")
            .addStatement("val api = %T()", DiscordApiBuilder::class)
            .addStatement("api.setToken(%S).login().join().addMessageCreateListener(::%N)", botConfig.token, onMessageFun)


        return runBuilder.build()
    }

    private fun createOnMessageFun(commandHandlers: Sequence<FunSpec>): FunSpec {
        val code = CodeBlock.builder()
            .beginControlFlow("when (%L)", "event.messageContent")

        commandHandlers.forEach { handler ->
            code.addStatement("%S -> event.channel.sendMessage(%N(event))", "!${handler.name}", handler)
        }

        code.endControlFlow()

        return FunSpec.builder("onMessage")
            .addParameter("event", MessageCreateEvent::class)
            .addCode(code.build())
            .build()
    }

    private fun createBotInstanceProperty(sourceClass: ClassName): PropertySpec {
        return PropertySpec.builder("bot", sourceClass)
            .initializer("%T()", sourceClass)
            .addModifiers(KModifier.PRIVATE)
            .build()
    }
}