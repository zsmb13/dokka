package org.jetbrains.dokka.base.signatures

import org.jetbrains.dokka.Platform
import org.jetbrains.dokka.base.transformers.pages.comments.CommentsToContentConverter
import org.jetbrains.dokka.base.translators.documentables.PageContentBuilder
import org.jetbrains.dokka.links.*
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.Nullable
import org.jetbrains.dokka.model.TypeConstructor
import org.jetbrains.dokka.model.properties.PropertyContainer
import org.jetbrains.dokka.model.properties.WithExtraProperties
import org.jetbrains.dokka.pages.ContentKind
import org.jetbrains.dokka.pages.ContentNode
import org.jetbrains.dokka.pages.TextStyle
import org.jetbrains.dokka.utilities.DokkaLogger
import kotlin.text.Typography.nbsp

class KotlinSignatureProvider(ctcc: CommentsToContentConverter, logger: DokkaLogger) : SignatureProvider,
    JvmSignatureUtils by KotlinSignatureUtils {
    private val contentBuilder = PageContentBuilder(ctcc, this, logger)

    private val ignoredVisibilities = setOf(JavaVisibility.Public, KotlinVisibility.Public)
    private val ignoredModifiers = setOf(JavaModifier.Final, KotlinModifier.Final)
    private val ignoredExtraModifiers = setOf(
        ExtraModifiers.KotlinOnlyModifiers.TailRec,
        ExtraModifiers.KotlinOnlyModifiers.External
    )
    private val platformSpecificModifiers : Map<ExtraModifiers, Set<Platform>> = mapOf(
        ExtraModifiers.KotlinOnlyModifiers.External to setOf(Platform.js)
    )

    override fun signature(documentable: Documentable): List<ContentNode> = when (documentable) {
        is DFunction -> functionSignature(documentable)
        is DProperty -> propertySignature(documentable)
        is DClasslike -> classlikeSignature(documentable)
        is DTypeParameter -> signature(documentable)
        is DEnumEntry -> signature(documentable)
        is DTypeAlias -> signature(documentable)
        else -> throw NotImplementedError(
            "Cannot generate signature for ${documentable::class.qualifiedName} ${documentable.name}"
        )
    }

    private fun <T> PageContentBuilder.DocumentableContentBuilder.processExtraModifiers (t: T)
            where T: Documentable, T: WithExtraProperties<T> {
        sourceSetDependentText(
            t.modifiers()
                .mapValues { entry ->
                    entry.value.filter {
                        it !in ignoredExtraModifiers || entry.key.platform in (platformSpecificModifiers[it] ?: emptySet())
                    }
                }
        ) {
            it.toSignatureString()
        }
    }

    private fun signature(e: DEnumEntry): List<ContentNode> =
        e.sourceSets.map {
            contentBuilder.contentFor(
                e,
                ContentKind.Symbol,
                setOf(TextStyle.Monospace, TextStyle.Block) + e.stylesForDeprecated(it),
                sourceSets = setOf(it)
            ) {
                group(styles = setOf(TextStyle.Block)) {
                    annotationsBlock(e)
                    link(e.name, e.dri, styles = emptySet())
                    e.extra[ConstructorValues]?.let { constructorValues ->
                        constructorValues.values[it]
                        text(constructorValues.values[it]?.joinToString(prefix = "(", postfix = ")") ?: "")
                    }
                }
            }
        }

    private fun actualTypealiasedSignature(c: DClasslike, sourceSet: SourceSetData, aliasedType: Bound) =
        contentBuilder.contentFor(
            c,
            ContentKind.Symbol,
            setOf(TextStyle.Monospace) + ((c as? WithExtraProperties<out Documentable>)?.stylesForDeprecated(sourceSet)
                ?: emptySet()),
            sourceSets = setOf(sourceSet)
        ) {
            text("actual typealias ")
            link(c.name.orEmpty(), c.dri)
            text(" = ")
            signatureForProjection(aliasedType)
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T : DClasslike> classlikeSignature(c: T): List<ContentNode> =
        c.sourceSets.map { sourceSetData ->
            (c as? WithExtraProperties<out DClasslike>)?.extra?.get(ActualTypealias)?.underlyingType?.get(sourceSetData)
                ?.let {
                    actualTypealiasedSignature(c, sourceSetData, it)
                } ?: regularSignature(c, sourceSetData)
        }


    private fun regularSignature(c: DClasslike, sourceSet: SourceSetData) =
        contentBuilder.contentFor(
            c,
            ContentKind.Symbol,
            setOf(TextStyle.Monospace) + ((c as? WithExtraProperties<out Documentable>)?.stylesForDeprecated(sourceSet)
                ?: emptySet()),
            sourceSets = setOf(sourceSet)
        ) {
            annotationsBlock(c)
            text(c.visibility[sourceSet]?.takeIf { it !in ignoredVisibilities }?.name?.let { "$it " } ?: "")
            if (c is DClass) {
                text(
                    if (c.modifier[sourceSet] !in ignoredModifiers)
                        if (c.extra[AdditionalModifiers]?.content?.contains(ExtraModifiers.KotlinOnlyModifiers.Data) == true) ""
                        else (if (c.modifier[sourceSet] is JavaModifier.Empty) KotlinModifier.Open.name else c.name).let { "$it " }
                    else
                        ""
                )
            }
            if (c is DInterface) {
                c.extra[AdditionalModifiers]?.content?.let { additionalModifiers ->
                    sourceSetDependentText(additionalModifiers, setOf(sourceSet)) { extraModifiers ->
                        if (ExtraModifiers.KotlinOnlyModifiers.Fun in extraModifiers) "fun "
                        else ""
                    }
                }
            }
            when (c) {
                is DClass -> {
                    processExtraModifiers(c)
                    text("class ")
                }
                is DInterface -> {
                    processExtraModifiers(c)
                    text("interface ")
                }
                is DEnum -> {
                    processExtraModifiers(c)
                    text("enum ")
                }
                is DObject -> {
                    processExtraModifiers(c)
                    text("object ")
                }
                is DAnnotation -> {
                    processExtraModifiers(c)
                    text("annotation class ")
                }
            }
            link(c.name!!, c.dri)
            if (c is WithGenerics) {
                list(c.generics, prefix = "<", suffix = "> ") {
                    +buildSignature(it)
                }
            }
            if (c is WithConstructors) {
                val pConstructor = c.constructors.singleOrNull { it.extra[PrimaryConstructorExtra] != null }
                if (pConstructor?.annotations()?.values?.any { it.isNotEmpty() } == true) {
                    text(nbsp.toString())
                    annotationsInline(pConstructor)
                    text("constructor")
                }
                list(
                    pConstructor?.parameters.orEmpty(),
                    "(",
                    ")",
                    ",",
                    pConstructor?.sourceSets.orEmpty().toSet()
                ) {
                    annotationsInline(it)
                    text(it.name ?: "", styles = mainStyles.plus(TextStyle.Bold))
                    text(": ")
                    signatureForProjection(it.type)
                }
            }
            if (c is WithSupertypes) {
                c.supertypes.filter { it.key == sourceSet }.map { (s, dris) ->
                    list(dris, prefix = " : ", sourceSets = setOf(s)) {
                        link(it.sureClassNames, it, sourceSets = setOf(s))
                    }
                }
            }
        }

    private fun propertySignature(p: DProperty) =
        p.sourceSets.map {
            contentBuilder.contentFor(p, ContentKind.Symbol, setOf(TextStyle.Monospace) + p.stylesForDeprecated(it), sourceSets = setOf(it)) {
                annotationsBlock(p)
                text(p.visibility[it].takeIf { it !in ignoredVisibilities }?.name?.let { "$it " } ?: "")
                text(
                    p.modifier[it].takeIf { it !in ignoredModifiers }?.let {
                        if (it is JavaModifier.Empty) KotlinModifier.Open else it
                    }?.name?.let { "$it " } ?: ""
                )
                text(p.modifiers()[it]?.toSignatureString() ?: "")
                p.setter?.let { text("var ") } ?: text("val ")
                list(p.generics, prefix = "<", suffix = "> ") {
                    +buildSignature(it)
                }
                p.receiver?.also {
                    signatureForProjection(it.type)
                    text(".")
                }
                link(p.name, p.dri)
                text(": ")
                signatureForProjection(p.type)
            }
        }

    private fun functionSignature(f: DFunction) =
        f.sourceSets.map {
            contentBuilder.contentFor(f, ContentKind.Symbol, setOf(TextStyle.Monospace) + f.stylesForDeprecated(it), sourceSets = setOf(it)) {
                annotationsBlock(f)
                text(f.visibility[it]?.takeIf { it !in ignoredVisibilities }?.name?.let { "$it " } ?: "")
                text(f.modifier[it]?.takeIf { it !in ignoredModifiers }?.let {
                        if (it is JavaModifier.Empty) KotlinModifier.Open else it
                    }?.name?.let { "$it " } ?: ""
                )
                text(f.modifiers()[it]?.toSignatureString() ?: "")
                text("fun ")
                list(f.generics, prefix = "<", suffix = "> ") {
                    +buildSignature(it)
                }
                f.receiver?.also {
                    signatureForProjection(it.type)
                    text(".")
                }
                link(f.name, f.dri)
                text("(")
                list(f.parameters) {
                    annotationsInline(it)
                    processExtraModifiers(it)
                    text(it.name!!)
                    text(": ")
                    signatureForProjection(it.type)
                }
                text(")")
                if (f.documentReturnType()) {
                    text(": ")
                    signatureForProjection(f.type)
                }
            }
        }

    private fun DFunction.documentReturnType() = when {
        this.isConstructor -> false
        this.type is TypeConstructor && (this.type as TypeConstructor).dri == DriOfUnit -> false
        this.type is Void -> false
        else -> true
    }

    private fun signature(t: DTypeAlias) =
        t.sourceSets.map {
            contentBuilder.contentFor(t, styles = t.stylesForDeprecated(it), sourceSets = setOf(it)) {
                t.underlyingType.entries.groupBy({ it.value }, { it.key }).map { (type, platforms) ->
                    +contentBuilder.contentFor(
                        t,
                        ContentKind.Symbol,
                        setOf(TextStyle.Monospace),
                        sourceSets = platforms.toSet()
                    ) {
                        text(t.visibility[it]?.takeIf { it !in ignoredVisibilities }?.name?.let { "$it " } ?: "")
                        processExtraModifiers(t)
                        text("typealias ")
                        signatureForProjection(t.type)
                        text(" = ")
                        signatureForProjection(type)
                    }
                }
            }
        }

    private fun signature(t: DTypeParameter) =
        t.sourceSets.map {
            contentBuilder.contentFor(t, styles = t.stylesForDeprecated(it), sourceSets = setOf(it)) {
                link(t.name, t.dri.withTargetToDeclaration())
                list(t.bounds, prefix = " : ") {
                    signatureForProjection(it)
                }
            }
        }

    private fun PageContentBuilder.DocumentableContentBuilder.signatureForProjection(p: Projection): Unit =
        when (p) {
            is OtherParameter -> link(p.name, p.declarationDRI)

            is TypeConstructor -> if (p.function)
                +funType(mainDRI.single(), mainPlatformData, p)
            else
                group(styles = emptySet()) {
                    link(p.dri.classNames.orEmpty(), p.dri)
                    list(p.projections, prefix = "<", suffix = ">") {
                        signatureForProjection(it)
                    }
                }

            is Variance -> group(styles = emptySet()) {
                text(p.kind.toString() + " ")
                signatureForProjection(p.inner)
            }

            is Star -> text("*")

            is Nullable -> group(styles = emptySet()) {
                signatureForProjection(p.inner)
                text("?")
            }

            is JavaObject -> link("Any", DriOfAny)
            is Void -> link("Unit", DriOfUnit)
            is PrimitiveJavaType -> signatureForProjection(p.translateToKotlin())
            is Dynamic -> text("dynamic")
            is UnresolvedBound -> text(p.name)
        }

    private fun funType(dri: DRI, sourceSets: Set<SourceSetData>, type: TypeConstructor) =
        contentBuilder.contentFor(dri, sourceSets, ContentKind.Main) {
            if (type.extension) {
                signatureForProjection(type.projections.first())
                text(".")
            }

            val args = if (type.extension)
                type.projections.drop(1)
            else
                type.projections

            text("(")
            args.subList(0, args.size - 1).forEachIndexed { i, arg ->
                signatureForProjection(arg)
                if (i < args.size - 2) text(", ")
            }
            text(") -> ")
            signatureForProjection(args.last())
        }
}

private fun PrimitiveJavaType.translateToKotlin() = TypeConstructor(
    dri = DRI("kotlin", name.capitalize()),
    projections = emptyList()
)

val TypeConstructor.function
    get() = modifier == FunctionModifiers.FUNCTION || modifier == FunctionModifiers.EXTENSION

val TypeConstructor.extension
    get() = modifier == FunctionModifiers.EXTENSION
