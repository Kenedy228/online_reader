const path = require('path');

module.exports = {
    entry: {
        passwordVisibilityChangerForLogin: "./src/passwordVisibilityChanger/login/login.ts",
        passwordVisibilityChangerForRegistration: "./src/passwordVisibilityChanger/registration/registration.ts",
    },
    output: {
        filename: '[name].bundle.js', // [name] будет заменено на имя входной точки
        path: process.env.WEBPACK_OUTPUT_PATH
            ? path.resolve(process.env.WEBPACK_OUTPUT_PATH)
            : path.resolve(__dirname, '../resources/static/js'),
    },
    resolve: {
        extensions: ['.ts', '.js'], // Разрешаем импорт файлов с указанными расширениями
    },
    module: {
        rules: [
            {
                test: /\.ts$/, // Обрабатываем только .ts файлы
                use: 'ts-loader', // Используем ts-loader для компиляции TypeScript
                exclude: /node_modules/,
            },
        ],
    },
};
