import re

def to_snake_case(text):
    # Convert "can write locks" to "can_write_locks"
    return text.strip().replace(' ', '_').replace(':', '').replace('-', '_').lower()

def should_add_delineator(lines, idx):
    """Check if delineator already exists at this line"""
    if idx > 0 and '***DELINEATOR FOR REVIEW' in lines[idx - 1]:
        return False
    return True

with open('/repo/src/test/groovy/com/palantir/gradle/versions/VersionsLockPluginIntegrationSpec.groovy', 'r') as f:
    lines = f.readlines()

new_lines = []
i = 0
in_test_method = False
is_first_statement_in_test = False

while i < len(lines):
    line = lines[i]

    # Check for test method definitions
    if re.match(r"    def '#gradleVersionNumber: (.+)'\(\) \{", line):
        match = re.match(r"    def '#gradleVersionNumber: (.+)'\(\) \{", line)
        method_name = to_snake_case(match.group(1))
        if should_add_delineator(new_lines, len(new_lines)):
            new_lines.append(f"    // ***DELINEATOR FOR REVIEW: {method_name}\n")
        new_lines.append(line)
        in_test_method = True
        is_first_statement_in_test = True
        i += 1
        continue

    # Check if we're leaving a test method
    if in_test_method and re.match(r"    \}\s*$", line):
        in_test_method = False
        new_lines.append(line)
        i += 1
        continue

    # Handle Spock keywords (when:, then:, expect:) inside test methods
    if in_test_method and re.match(r"\s+(when|then|expect):\s*", line):
        keyword_match = re.match(r"\s+(when|then|expect):\s*", line)
        keyword = keyword_match.group(1)
        # Add delineator for keywords (but not before setup: at start)
        if not is_first_statement_in_test:
            if should_add_delineator(new_lines, len(new_lines)):
                indent = ' ' * (len(line) - len(line.lstrip()))
                new_lines.append(f"{indent}// ***DELINEATOR FOR REVIEW: {keyword}\n")
        new_lines.append(line)
        is_first_statement_in_test = False
        i += 1
        continue

    # If we see any other non-empty, non-comment line in a test method, it's no longer the first statement
    if in_test_method and line.strip() and not line.strip().startswith('//') and not line.strip().startswith('setup:'):
        is_first_statement_in_test = False

    new_lines.append(line)
    i += 1

with open('/repo/src/test/groovy/com/palantir/gradle/versions/VersionsLockPluginIntegrationSpec.groovy', 'w') as f:
    f.writelines(new_lines)

print("Delineators added successfully")
