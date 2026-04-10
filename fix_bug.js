const fs = require('fs');

const path = '/home/kweezy/Desktop/GostForge/vscode-extension/src/api/client.ts';
let code = fs.readFileSync(path, 'utf8');

console.log(code.match(/checkHashes\([\s\S]*?\{[\s\S]*?request\(/)[0]);
