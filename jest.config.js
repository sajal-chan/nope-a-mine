module.exports = {
  preset: 'react-native',
  testTimeout: 15000,
  setupFilesAfterEnv: ['./jest.setup.js'],
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?)/|react-native-safe-area-context/)',
  ],
};
