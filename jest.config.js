module.exports = {
  preset: '@react-native/jest-preset',
  roots: ['<rootDir>/src'],
  modulePathIgnorePatterns: ['<rootDir>/lib/', '<rootDir>/example/'],
  testTimeout: 30000,
};
