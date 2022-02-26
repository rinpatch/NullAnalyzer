import org.jetbrains.annotations.NotNull;

public class RedundantNullCheck {
    void literal() {
        String test = "";
        if (test != null) {
            throw IllegalStateException("wtf");
        }
    }

    void enclosedLiteral() {
        String test = ("");
        if (test != null) {
            throw IllegalStateException("wtf");
        }
    }
    void annotation(@NotNull String test) {
        if (test != null) {
            throw IllegalStateException("wtf");
        }
    }
    void variableCompare(@NotNull String test) {
        String test2 = null;
        if (test == test2) {
            throw IllegalStateException("wtf");
        }
    }
    void redundantNullCheck(@NotNull String test) {
        if (test == null) {
            throw IllegalStateException("");
        }
    }
    String nestedRedundantNullCheck(@NotNull String test, bool cond1, bool cond2, bool cond3) {
       if (cond1) {
           if (cond2) {
               return "1";
           } else {
               return "2";
           }
       } else {
           if (cond3) {
               return "3";
           } else {
               if (test != null) {
                   return test;
               } else {
                   return "4";
               }
           }
       }
    }
    String complexBinaryExpresison(@NotNull String test, boolean cond1, boolean cond2, boolean cond3) {
        if (cond1 || (cond2 && (cond3 || test != null))) {
            return test + "test success";
        } else {
            return "test failed";
        }
    }
    String wrappedComplexBinaryExpresison(@NotNull String test, boolean cond1, boolean cond2, boolean cond3) {
        if ((((cond1))) || (((cond2)) && ((((((((((cond3) || ((((((((((test != null))))))))))))))))))))) {
            return test + "test success";
        } else {
            return "test failed";
        }
    }

    String bait(String test) {
        if ((test != null) || 1 == 1) {
            return test + "test success";
        } else {
            return "test failed";
        }
    }
}