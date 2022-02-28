# NullAnalyzer
## Limitations
The analyzer does not currently track the status of fields.

So, for example the following code should not produce errors, but will:
```java
void foo(@NotNull Point p) {
}

void test(@NotNull Point p) {
    if (p.rightNeighbour != null){
        foo(p.rightNeighbour)
    }
}
```
## Building
```sh
gradle jar
```
## Usage
```sh
 java -jar build/libs/NullAnalyzer-1.0-SNAPSHOT.jar example.java out.json
```

## Example output

```json
[
  {
    "line": 14,
    "type": "REDUNDANT_NULL_CHECK",
    "offendingCode": "nonNullP == null"
  },
  {
    "line": 18,
    "type": "FUNCTION_CALL_MAY_BE_NULL",
    "offendingCode": "foo(nullP)"
  },
  {
    "line": 25,
    "type": "FIELD_ACCESS_MAY_BE_NULL",
    "offendingCode": "nullP.x"
  },
  {
    "line": 37,
    "type": "FIELD_ACCESS_IS_NULL",
    "offendingCode": "defaultP.x"
  },
  {
    "line": 43,
    "type": "FUNCTION_CALL_MAY_BE_NULL",
    "offendingCode": "foo(defaultP)"
  }
]
```