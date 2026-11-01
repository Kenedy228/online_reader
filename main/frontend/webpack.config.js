const path = require('path');

module.exports = (_environment, argv = {}) => {
    const mode = argv.mode || 'production';
    if (!['production', 'development'].includes(mode)) {
        throw new Error('Use production or development mode');
    }
    const entry = {
        passwordVisibilityChangerForLogin: './src/passwordVisibilityChanger/login/login.ts',
        passwordVisibilityChangerForRegistration: './src/passwordVisibilityChanger/registration/registration.ts',
    };
    const generated = new Set(Object.keys(entry).flatMap(name => [
        `${name}.bundle.js`, `${name}.bundle.js.map`, `${name}.bundle.js.LICENSE.txt`,
    ]));
    return {
        mode,
        devtool: mode === 'development' ? 'source-map' : false,
        entry,
        output: {
            filename: '[name].bundle.js',
            path: process.env.WEBPACK_OUTPUT_PATH
                ? path.resolve(process.env.WEBPACK_OUTPUT_PATH)
                : path.resolve(__dirname, '../resources/static/js'),
            // Remove stale development maps without deleting unrelated files in this directory.
            clean: { keep: asset => !generated.has(asset) },
        },
        resolve: { extensions: ['.ts', '.js'] },
        module: {
            rules: [{ test: /\.ts$/, use: 'ts-loader', exclude: /node_modules/ }],
        },
    };
};
