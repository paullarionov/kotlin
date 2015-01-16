package evProperty

class A {
    val prop = 1
}

fun main(args: Array<String>) {
    val a = A()
    val b = a.prop
    //Breakpoint!
}

// PRINT_FRAME