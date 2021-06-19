from string import Template
from pathlib import Path

dude = Template(Path('test.sql').read_text())


sqlscript = dude.substitute(name='Baahubali')

with open('testout.sql', 'w') as file:
    file.write(sqlscript)
