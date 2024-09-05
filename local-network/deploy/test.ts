import path from 'path';

const args: string[] = process.argv.slice(2);
let filePath: string = args[0]; // Access the first command-line argument as the file path

filePath = path.resolve(filePath);
if (filePath.endsWith(".ts")) filePath = filePath.substring(0, filePath.length - ".ts".length)

// Dynamically import the module at the resolved file path
const modulePromise: Promise<any> = import(filePath);
modulePromise.then((module) => {
  console.log('Module loaded successfully:', module);
}).catch((error) => {
  console.error('Error loading module:', error);
});
