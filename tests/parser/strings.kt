val a = "plain\n\t\"\u0041\$"
val b = "x=$x, y=${y.z}"
val c = """raw $a ${b} \n"""
val d = $$"""$x $$y ${z}"""
val e = 'c'
val f = '\''
val g = "${"nested ${deep}"}"
