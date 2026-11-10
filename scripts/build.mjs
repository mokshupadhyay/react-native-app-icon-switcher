import { execFileSync } from 'node:child_process';
import { rmSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const tsc = require.resolve('typescript/bin/tsc');

const run = (...args) =>
  execFileSync(process.execPath, [tsc, '-p', 'tsconfig.build.json', ...args], {
    stdio: 'inherit',
  });

rmSync('lib', { recursive: true, force: true });

// ES2017 output keeps optional chaining and nullish coalescing out of the
// shipped JavaScript, so Metro releases bundled with React Native 0.60 can
// parse it. The classic JSX transform keeps React 16.8 compatibility.
run('--module', 'commonjs', '--outDir', 'lib/commonjs');
run('--module', 'esnext', '--outDir', 'lib/module');
run(
  '--declaration',
  '--emitDeclarationOnly',
  '--sourceMap',
  'false',
  '--outDir',
  'lib/typescript',
);
