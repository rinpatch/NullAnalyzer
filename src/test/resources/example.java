public class Example {
        void foo(@NotNull Point p) {
        }

        void bar(Point p) {
        }

        void sample(
                @NotNull Point nonNullP, // definitely not null
                @Nullable Point nullP, // may be null and need checks
                Point defaultP // may be null but we don't care
        ) {

                if (nonNullP == null) {
                        // ^^^^^^^ --- error: redundant null check
                        int x = nonNullP.x; // no check needed
                }
                foo(nullP);
                // ^^^^^^ --- error: function foo requires not null parameter, but nullP may be null

                if (nullP != null) {
                        foo(nullP); // no error: nullP is not null here
                }

                int z = nullP.x;
                //       ^^^^^^ --- error:  nullP may be null

                bar(nullP); // no error: bar parameter is not marked as @NotNull

                if (defaultP != null) {
                        foo(defaultP); // no error: defaultP is not null here
                }

                int a = defaultP.x; // no error: defaultP may be null, but we don't care

                if (defaultP == null && true == true) {
                        int b = defaultP.x;
                        //      ^^^^^^^^^^ --- error: defaultP is always null here
                }

                bar(defaultP); // no error

                foo(defaultP);
                // ^^^^^^ --- error: function foo requires not null parameter, but defaultP may be null
        }
}