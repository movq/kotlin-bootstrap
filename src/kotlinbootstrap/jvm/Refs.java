package kotlinbootstrap.jvm;

import java.lang.constant.ClassDesc;

/** The kotlin.jvm.internal.Ref classes that hold variables captured and changed by closures. */
final class Refs {
    private Refs() {
    }

    /** The Ref class for a value of the type: IntRef for int, ..., ObjectRef for references. */
    static ClassDesc refClass(ClassDesc type) {
        String name = switch (type.descriptorString()) {
            case "Z" -> "BooleanRef";
            case "C" -> "CharRef";
            case "B" -> "ByteRef";
            case "S" -> "ShortRef";
            case "I" -> "IntRef";
            case "J" -> "LongRef";
            case "F" -> "FloatRef";
            case "D" -> "DoubleRef";
            default -> "ObjectRef";
        };
        return ClassDesc.ofInternalName("kotlin/jvm/internal/Ref$" + name);
    }

    /** The type of a Ref's element field. */
    static ClassDesc elementType(ClassDesc type) {
        return type.isPrimitive() ? type : TypeMapper.OBJECT;
    }
}
