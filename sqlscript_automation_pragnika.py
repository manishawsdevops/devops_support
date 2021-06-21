from string import Template
from pathlib import Path

output_sql_script = Template(Path('test.sql').read_text())


sqlscript = output_sql_script.substitute(name='Baahubali')

with open('testout.sql', 'w') as file:
    file.write(sqlscript)
